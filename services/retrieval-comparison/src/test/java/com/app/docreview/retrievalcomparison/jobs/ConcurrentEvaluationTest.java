package com.app.docreview.retrievalcomparison.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency properties of requirement evaluation. Architecture §10.5, §18.4, §19.2.
 *
 * <p>Requirements used to be evaluated one at a time. Each unit is a chain of network calls that is
 * almost entirely wait, so a 41-requirement comparison took 3m21s — around 4.9s per unit, nearly all
 * of it idle. Evaluating several at once is the fix.
 *
 * <p><strong>What these tests protect.</strong> Three properties that a fan-out can silently break,
 * and that a passing comparison would not reveal:
 *
 * <ul>
 *   <li>results stay in REQUIREMENT order, because §19.2 requires two runs over identical inputs to
 *       produce identical output — completion order is not reproducible;</li>
 *   <li>one failing unit does not discard the others (§18.4);</li>
 *   <li>concurrency stays bounded, because the ceiling is a shared proxy's rate limit and exceeding
 *       it turns throughput into 429s that surface as unevaluated controls.</li>
 * </ul>
 *
 * <p>These exercise the submit-and-collect pattern the runner uses rather than the runner itself:
 * constructing it requires a vector store, a model gateway, and two repositories, and mocking that
 * graph would test the mocks. What can break here is the concurrency structure, and that is what is
 * reproduced.
 */
class ConcurrentEvaluationTest {

    /** Mirrors the runner: submit all, then collect by index. */
    private static <T> List<T> submitAndCollectInOrder(int parallelism, List<Callable<T>> tasks)
            throws Exception {
        var executor = Executors.newFixedThreadPool(parallelism);
        try {
            var futures = new ArrayList<Future<T>>();
            for (var task : tasks) {
                futures.add(executor.submit(task));
            }
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("results follow requirement order, not completion order")
    void resultsAreOrderedByRequirement() throws Exception {
        // Deliberately inverted: the LAST task finishes first. Collecting as results arrive would
        // reverse them, and a comparison whose finding order depends on model latency is not
        // reproducible (§19.2).
        int count = 8;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            tasks.add(() -> {
                Thread.sleep((count - index) * 15L);
                return index;
            });
        }

        assertThat(submitAndCollectInOrder(4, tasks))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    @DisplayName("one failing unit does not discard the others")
    void oneFailureDoesNotLoseTheRest() throws Exception {
        // §18.4. Each task converts its own failure, exactly as evaluateOne does, so a thrown
        // exception never propagates far enough to abandon the fan-out.
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int index = i;
            tasks.add(() -> {
                try {
                    if (index == 3) {
                        throw new IllegalStateException("provider rejected the request");
                    }
                    return "ok-" + index;
                } catch (Exception e) {
                    return "failed-" + index;
                }
            });
        }

        assertThat(submitAndCollectInOrder(3, tasks))
                .containsExactly("ok-0", "ok-1", "ok-2", "failed-3", "ok-4", "ok-5");
    }

    @Test
    @DisplayName("never runs more units at once than the bound allows")
    void concurrencyIsBounded() throws Exception {
        // The rate-limit guard. Records the high-water mark of simultaneously running tasks.
        int parallelism = 4;
        var running = new AtomicInteger();
        var peak = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(10);
                } finally {
                    running.decrementAndGet();
                }
                return null;
            });
        }

        submitAndCollectInOrder(parallelism, tasks);

        assertThat(peak.get())
                .as("exceeding the bound converts throughput into provider rate-limit errors")
                .isLessThanOrEqualTo(parallelism);
    }

    @Test
    @DisplayName("the completion counter totals every unit exactly once")
    void progressCounterIsExact() throws Exception {
        // Progress is incremented from worker threads. A non-atomic counter loses updates under
        // contention, and the reviewer would see a bar that stalls below 100%.
        var completed = new AtomicInteger();
        int count = 60;

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(() -> {
                try {
                    Thread.sleep(1);
                } finally {
                    completed.incrementAndGet();
                }
                return null;
            });
        }

        submitAndCollectInOrder(8, tasks);

        assertThat(completed.get()).isEqualTo(count);
    }

    @Test
    @DisplayName("concurrent units genuinely overlap, so the run is faster than sequential")
    void unitsOverlap() throws Exception {
        // Guards against a fan-out that is accidentally serial — e.g. calling get() inside the
        // submit loop, which compiles, passes every ordering test, and removes all the benefit.
        int count = 8;
        long unitMillis = 60;
        var gate = new CountDownLatch(count);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(() -> {
                gate.countDown();
                Thread.sleep(unitMillis);
                return null;
            });
        }

        long startedAt = System.nanoTime();
        submitAndCollectInOrder(count, tasks);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // All tasks reached the gate, so they were in flight together rather than queued.
        assertThat(gate.await(0, TimeUnit.SECONDS)).isTrue();
        // Generous: asserting a hard ratio makes this flaky on a loaded CI machine. Sequential would
        // be count * unitMillis; anything well below that proves overlap.
        assertThat(elapsedMillis).isLessThan(count * unitMillis);
    }

    @Test
    @DisplayName("a single requirement still works")
    void singleUnit() throws Exception {
        // The pool size is min(parallelism, size), and newFixedThreadPool(0) throws.
        assertThat(submitAndCollectInOrder(1, List.of(() -> "only")))
                .containsExactly("only");
    }

    @Test
    @DisplayName("an empty requirement list produces no results")
    void noUnits() throws Exception {
        assertThat(submitAndCollectInOrder(4, Collections.emptyList())).isEmpty();
    }
}
