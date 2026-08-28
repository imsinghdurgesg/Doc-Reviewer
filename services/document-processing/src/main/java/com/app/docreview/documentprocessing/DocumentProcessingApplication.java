package com.app.docreview.documentprocessing;

import com.app.docreview.documentprocessing.storage.MinioProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the document-processing service. Architecture §8.2.
 *
 * <p>An internal worker: no browser-facing surface (§3) and no LiteLLM client at all (§8.2). Parsing
 * is deterministic and must stay so — §39 rule 17 requires it to happen before any model is involved.
 */
@SpringBootApplication
@EnableConfigurationProperties({MinioProperties.class,
        com.app.docreview.documentprocessing.storage.GcsProperties.class})
public class DocumentProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentProcessingApplication.class, args);
    }
}
