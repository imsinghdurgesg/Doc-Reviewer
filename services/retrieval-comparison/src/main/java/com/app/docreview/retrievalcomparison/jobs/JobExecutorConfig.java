package com.app.docreview.retrievalcomparison.jobs;

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
 * The bounded executor that runs claimed jobs. Architecture §21 step 5, §10.5.
 *
 * <p>The bound here is about SPEND, not memory. Each unit of work is a chain of LiteLLM calls, so
 * unbounded concurrency means an unbounded number of concurrent model requests against the
 * organizational proxy — exhausting its rate limits and the application's budget (§10.5), and
 * degrading the proxy for every other team using it. This is why the default thread count is lower
 * than document-processing's.
 *
 * <p>The queue is bounded for the same reason as elsewhere: an unbounded queue accepts work
 * indefinitely while reporting healthy, so the service falls permanently behind invisibly. A
 * rejection is visible, and the job stays claimed for the recovery sweep (§21 step 8).
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
            @Value("${docreview.jobs.worker-threads:2}") int workerThreads,
            @Value("${docreview.jobs.queue-capacity:32}") int queueCapacity) {

        log.info("Job executor: {} threads, queue capacity {}", workerThreads, queueCapacity);

        var executor = new ThreadPoolExecutor(
                workerThreads, workerThreads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    var thread = new Thread(runnable);
                    thread.setName("rc-job-" + thread.threadId());
                    // Daemon so a stalled model call cannot hold the JVM open through shutdown.
                    thread.setDaemon(true);
                    return thread;
                },
                // A typed exception rather than CallerRunsPolicy: running the work on the request
                // thread would block the 202 and make dispatch time out — the exact failure the
                // 202 exists to prevent.
                (runnable, pool) -> {
                    throw new JobRejectedException(
                            "Job executor saturated: " + pool.getQueue().size() + " queued");
                });

        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
