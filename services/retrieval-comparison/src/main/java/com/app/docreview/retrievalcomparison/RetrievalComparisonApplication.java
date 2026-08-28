package com.app.docreview.retrievalcomparison;

import com.app.docreview.retrievalcomparison.llm.litellm.LiteLlmProperties;
import com.app.docreview.retrievalcomparison.vector.qdrant.QdrantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for retrieval-comparison. Architecture §8.3.
 *
 * <p>The only service holding LiteLLM credentials and the only one that talks to Qdrant. Retrieval
 * and comparison are separate packages inside one deployable (§2), so they can be split later
 * without moving a service boundary.
 */
@SpringBootApplication
// Both @ConfigurationProperties records must be listed. A record without a @Component-style
// annotation is not picked up by scanning, and the omission is invisible until the context starts:
// the failure is "no qualifying bean of type QdrantProperties", which names the symptom rather than
// the missing registration.
@EnableConfigurationProperties({LiteLlmProperties.class, QdrantProperties.class})
public class RetrievalComparisonApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalComparisonApplication.class, args);
    }
}
