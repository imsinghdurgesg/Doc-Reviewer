package com.app.docreview.reviewcore;

import com.app.docreview.reviewcore.security.DemoUserProperties;
import com.app.docreview.reviewcore.security.sso.SsoProperties;
import com.app.docreview.reviewcore.storage.gcs.GcsProperties;
import com.app.docreview.reviewcore.storage.minio.MinioProperties;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for review-core. Architecture §8.1.
 *
 * <p>The only backend the browser reaches (§3). Holds no LiteLLM credentials and no Qdrant client by
 * design: a model call from this service is impossible rather than merely discouraged.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        MinioProperties.class, GcsProperties.class, DemoUserProperties.class,
        SsoProperties.class, JobDispatchProperties.class})
// Drives the stale-job recovery sweep (§21 step 8), which is what makes durable jobs actually
// recover rather than merely being recorded.
@EnableScheduling
public class ReviewCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewCoreApplication.class, args);
    }
}
