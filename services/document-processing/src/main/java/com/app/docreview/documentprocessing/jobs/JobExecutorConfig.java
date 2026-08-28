package com.app.docreview.documentprocessing.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The bounded executor that runs claimed jobs. Architecture §21 step 5.
 *
 * <p>Bounded in BOTH dimensions, and both bounds matter:
 *
 * <ul>
 *   <li><strong>Threads.</strong> Parsing holds a whole document and its text positions in memory
 *       (§8.2 limits exist for exactly this reason). Unbounded threads means N concurrent uploads
 *       each holding hundreds of megabytes, and the container is OOM-killed.</li>
 *   <li><strong>Queue.</strong> An unbounded queue does not drop work — it defers it indefinitely
 *       while accepting more, so the service reports healthy while falling permanently behind. A
 *       bounded queue rejects, and a rejection is visible: the job stays non-terminal and the
 *       recovery sweep re-dispatches it later (§21 step 8).</li>
 * </ul>
 *
 * <p>Platform threads, not virtual ones. Parsing is CPU- and memory-bound, not IO-bound; virtual
 * threads would allow thousands of concurrent parses to be admitted and would defeat the memory
 * bound above. {@code spring.threads.virtual.enabled} still applies to request handling, which IS
 * IO-bound — this executor deliberately opts out for the work itself.
 */
@Configuration
@Slf4j
public class JobExecutorConfig {

    /** Marks a rejected submission so the caller can leave the job for the recovery sweep. */
    public static class JobRejectedException extends RejectedExecutionException {
        public JobRejectedException(String message) {
            super(message);
        }
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService jobExecutor(
            @Value("${docreview.jobs.worker-threads:4}") int workerThreads,
            @Value("${docreview.jobs.queue-capacity:32}") int queueCapacity) {

        log.info("Job executor: {} threads, queue capacity {}", workerThreads, queueCapacity);

        var executor = new ThreadPoolExecutor(
                workerThreads, workerThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    var thread = new Thread(runnable);
                    thread.setName("doc-job-" + thread.threadId());
                    // Daemon so a stuck parse cannot keep the JVM alive through shutdown; the
                    // recovery sweep re-dispatches whatever was interrupted.
                    thread.setDaemon(true);
                    return thread;
                },
                // Translate saturation into a typed exception rather than silently running the work
                // on the request thread (CallerRunsPolicy), which would block the 202 response and
                // make dispatch time out — the failure the 202 exists to avoid.
                (runnable, pool) -> {
                    throw new JobRejectedException(
                            "Job executor saturated: " + pool.getQueue().size() + " queued");
                });

        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
