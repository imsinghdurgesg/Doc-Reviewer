package com.app.docreview.retrievalcomparison.vector.qdrant;

import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Qdrant implementation of {@link VectorStorePort}. Architecture §9.6, §13.2.
 *
 * <p>The only class that knows Qdrant exists, so §41's "replace individual adapters" stays real.
 *
 * <p><strong>Point IDs are UUIDs derived from the application ID.</strong> Qdrant accepts only
 * {@code long} or {@code UUID} — verified against the 1.15.0 client, where {@code PointIdFactory.id}
 * has no String overload. Deriving a v3-style UUID from the application ID keeps upserts idempotent:
 * re-indexing the same chunk overwrites its point instead of adding a second one. A random UUID would
 * make every re-index double the search space while every result still looked plausible.
 *
 * <p>The client is async-only ({@code ListenableFuture}). Calls are awaited here because indexing and
 * retrieval are already inside bounded worker paths, and an async API leaking into retrieval logic
 * would buy nothing but complexity.
 */
@Component
@Slf4j
public class QdrantVectorStore implements VectorStorePort {

    private final QdrantClient client;

    public QdrantVectorStore(QdrantProperties properties) {
        var builder = QdrantGrpcClient.newBuilder(
                properties.host(), properties.port(), properties.useTls());

        // withApiKey is called ONLY when a key exists. Passing null does not mean "no auth" — the
        // client still installs an API-key call interceptor, which then throws
        // NullPointerException: value while building the header, and gRPC surfaces that as
        // UNAUTHENTICATED. The result is an auth error against a Qdrant that has auth disabled,
        // which sends debugging at the server instead of at this line.
        boolean hasApiKey = properties.apiKey() != null && !properties.apiKey().isBlank();
        if (hasApiKey) {
            builder = builder.withApiKey(properties.apiKey());
        }

        this.client = new QdrantClient(builder.build());
        log.info("Qdrant client -> {}:{} (tls={}, apiKey={})",
                properties.host(), properties.port(), properties.useTls(),
                hasApiKey ? "set" : "none");
    }

    @Override
    public void ensureCollection(VectorCollection collection, int dimensions, String distanceMetric) {
        String name = collection.collectionName();
        try {
            if (Boolean.TRUE.equals(client.collectionExistsAsync(name).get())) {
                // Deliberately NOT recreated. §13.4 forbids mixing incompatible vectors, and
                // silently recreating would delete a live index; an incompatible change must be a
                // new collection name, which is a decision rather than a side effect.
                log.debug("Collection {} already exists", name);
                return;
            }
            client.createCollectionAsync(name, VectorParams.newBuilder()
                    .setSize(dimensions)
                    .setDistance(distance(distanceMetric))
                    .build()).get();
            log.info("Created Qdrant collection {} ({} dims, {})", name, dimensions, distanceMetric);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure collection " + name, e);
        }
    }

    @Override
    public void upsert(VectorCollection collection, String id, float[] vector,
                       Map<String, Object> payload) {
        upsertBatch(collection, List.of(new VectorPoint(id, vector, payload)));
    }

    @Override
    public void upsertBatch(VectorCollection collection, List<VectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        try {
            List<PointStruct> structs = points.stream()
                    .map(p -> PointStruct.newBuilder()
                            .setId(id(deterministicUuid(p.id())))
                            .setVectors(vectors(p.vector()))
                            .putAllPayload(toPayload(p.payload()))
                            .build())
                    .toList();
            client.upsertAsync(collection.collectionName(), structs).get();
            log.debug("Upserted {} point(s) into {}", structs.size(), collection.collectionName());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to upsert into " + collection.collectionName(), e);
        }
    }

    @Override
    public List<SearchHit> search(VectorCollection collection, float[] queryVector, int limit,
                                 Map<String, Object> filter, Double minScore) {
        try {
            var request = SearchPoints.newBuilder()
                    .setCollectionName(collection.collectionName())
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(limit)
                    // Payload is required: spanIds live there, and they are what the model may cite.
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            if (filter != null && !filter.isEmpty()) {
                // Applied server-side, BEFORE scoring. Filtering the top-k afterwards is the classic
                // vector-search bug: ten results are requested, most are filtered out, and the
                // caller silently receives two.
                request.setFilter(toFilter(filter));
            }
            if (minScore != null) {
                request.setScoreThreshold(minScore.floatValue());
            }

            List<ScoredPoint> points = client.searchAsync(request.build()).get();
            return points.stream()
                    .map(p -> new SearchHit(
                            p.getId().getUuid(), p.getScore(), fromPayload(p.getPayloadMap())))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Search failed on " + collection.collectionName(), e);
        }
    }

    @Override
    public void deleteByFilter(VectorCollection collection, Map<String, Object> filter) {
        // An empty filter builds a Qdrant Filter with no `must` clauses, which matches EVERY point --
        // so an accidental empty map would silently wipe the collection instead of deleting nothing.
        // Refusing here rather than in each caller means no future caller can make that mistake.
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refusing to delete from " + collection.collectionName()
                            + " with an empty filter: that would delete every point in the collection");
        }
        try {
            client.deleteAsync(collection.collectionName(), toFilter(filter)).get();
            log.info("Deleted points from {} matching {}",
                    collection.collectionName(), filter.keySet());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Delete failed on " + collection.collectionName(), e);
        }
    }

    /**
     * Derives a stable UUID from an application ID.
     *
     * <p>{@code UUID.nameUUIDFromBytes} is a deterministic MD5-based v3 UUID. MD5's cryptographic
     * weakness is irrelevant here: this is an identity mapping, not a security boundary, and the
     * property that matters is that the same input always yields the same UUID.
     */
    private UUID deterministicUuid(String applicationId) {
        return UUID.nameUUIDFromBytes(applicationId.getBytes(StandardCharsets.UTF_8));
    }

    private Distance distance(String metric) {
        return switch (metric == null ? "cosine" : metric.toLowerCase()) {
            case "euclid", "euclidean", "l2" -> Distance.Euclid;
            case "dot", "dotproduct" -> Distance.Dot;
            // Cosine is the default because embedding models are trained for it; using Euclid on
            // unnormalized embeddings ranks by magnitude as much as by meaning.
            default -> Distance.Cosine;
        };
    }

    private Map<String, Value> toPayload(Map<String, Object> payload) {
        Map<String, Value> result = new HashMap<>();
        payload.forEach((key, raw) -> {
            if (raw == null) {
                return;   // omit rather than store a null: an absent key filters more predictably
            }
            result.put(key, switch (raw) {
                case String s -> value(s);
                case Integer i -> value(i.longValue());
                case Long l -> value(l);
                case Double d -> value(d);
                case Float f -> value(f.doubleValue());
                case Boolean b -> value(b);
                case List<?> list -> value(list.stream().map(o -> value(String.valueOf(o))).toList());
                default -> value(String.valueOf(raw));
            });
        });
        return result;
    }

    private Map<String, Object> fromPayload(Map<String, Value> payload) {
        Map<String, Object> result = new HashMap<>();
        payload.forEach((key, v) -> {
            if (v.hasStringValue()) {
                result.put(key, v.getStringValue());
            } else if (v.hasIntegerValue()) {
                result.put(key, v.getIntegerValue());
            } else if (v.hasDoubleValue()) {
                result.put(key, v.getDoubleValue());
            } else if (v.hasBoolValue()) {
                result.put(key, v.getBoolValue());
            } else if (v.hasListValue()) {
                result.put(key, v.getListValue().getValuesList().stream()
                        .map(Value::getStringValue).toList());
            }
        });
        return result;
    }

    /** All conditions are ANDed: filters here narrow scope rather than widen it. */
    private Filter toFilter(Map<String, Object> filter) {
        var builder = Filter.newBuilder();
        filter.forEach((key, val) -> builder.addMust(matchKeyword(key, String.valueOf(val))));
        return builder.build();
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float f : vector) {
            list.add(f);
        }
        return list;
    }

    @PreDestroy
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close Qdrant client: {}", e.getMessage());
        }
    }
}
