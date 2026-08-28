package com.app.docreview.retrievalcomparison.vector.qdrant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qdrant connection. Architecture §13.5.
 *
 * <p>Port 6334 is gRPC, which the Java client uses; 6333 is the REST port and will simply not
 * respond to the client. That mix-up presents as a connection error with no hint that the port is
 * the problem.
 */
@ConfigurationProperties(prefix = "docreview.qdrant")
public record QdrantProperties(
        String host,
        int port,
        boolean useTls,
        String apiKey
) {}
