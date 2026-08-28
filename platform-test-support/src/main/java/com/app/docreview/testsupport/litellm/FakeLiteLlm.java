package com.app.docreview.testsupport.litellm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * An OpenAI-compatible stand-in for the LiteLLM Proxy. Architecture §26.4, §36.
 *
 * <p>§26.4 forbids normal automated tests from calling the real proxy. This exists so the whole
 * pipeline — extraction, embedding, retrieval, comparison — can be proven end to end without
 * organizational model spend, without network access, and without a shared budget that makes test
 * results depend on other teams' usage.
 *
 * <p>It serves the two OpenAI-compatible routes the application actually uses (§10.3):
 *
 * <pre>
 *   POST /v1/chat/completions   requirement extraction and comparison
 *   POST /v1/embeddings         embeddings for indexing and retrieval
 * </pre>
 *
 * <h2>Why responses are computed rather than fixed</h2>
 *
 * <p>A single canned response would make every requirement extract identically and every comparison
 * return the same verdict, so a test could not distinguish "the pipeline routed evidence correctly"
 * from "the stub always says ALIGNED". Instead:
 *
 * <ul>
 *   <li><strong>Extraction</strong> echoes the span IDs actually present in the prompt. §12.8 rule 2
 *       rejects any requirement citing a span it was not given, so a stub that invented span IDs
 *       would fail validation and never exercise the happy path.</li>
 *   <li><strong>Comparison</strong> echoes the requirement ID and the process span IDs from the
 *       prompt, and derives its status from whether process evidence was supplied at all — which is
 *       the same distinction §17.3/§17.5 draw between MISSING and a supported verdict.</li>
 *   <li><strong>Embeddings</strong> are a deterministic hash of the input text, so identical text
 *       always yields an identical vector and similar text does NOT accidentally collide. Random
 *       vectors would make retrieval order vary run to run and every retrieval assertion flaky.</li>
 * </ul>
 *
 * <p>This is a fake, not a model: it proves plumbing, span-ownership, and validation wiring. It says
 * nothing about answer quality, which is what §36's golden fixtures and evaluation set are for.
 */
public final class FakeLiteLlm implements AutoCloseable {

    /** Dimensions of the stub's embedding vectors. Small — nothing here needs 1536 floats. */
    public static final int DIMENSIONS = 16;

    private final WireMockServer server;

    private FakeLiteLlm(WireMockServer server) {
        this.server = server;
    }

    /** Starts a fake proxy on a random free port. */
    public static FakeLiteLlm start() {
        var server = new WireMockServer(options()
                .dynamicPort()
                .extensions(new ChatTransformer(), new EmbeddingTransformer()));
        server.start();

        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("litellm-chat")));

        server.stubFor(post(urlPathEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("litellm-embeddings")));

        return new FakeLiteLlm(server);
    }

    /**
     * Base URL to hand to {@code LITELLM_BASE_URL}.
     *
     * <p>Deliberately WITHOUT a {@code /v1} suffix: §10.3 notes the OpenAI client appends the version
     * segment itself, and including it here would produce {@code /v1/v1/...} — a 404 that reads like a
     * routing bug rather than a configuration one.
     */
    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public int port() {
        return server.port();
    }

    /** Number of chat calls received. Lets a test assert §12.1's "extract once, reuse" rule. */
    public int chatCallCount() {
        return server.getServeEvents().getServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().contains("/chat/completions"))
                .toList().size();
    }

    public int embeddingCallCount() {
        return server.getServeEvents().getServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().contains("/embeddings"))
                .toList().size();
    }

    public void resetCalls() {
        server.resetRequests();
    }

    @Override
    public void close() {
        server.stop();
    }

    // ---------------------------------------------------------------------------------------
    // Chat: requirement extraction and comparison
    // ---------------------------------------------------------------------------------------

    /**
     * Answers chat calls, choosing extraction or comparison by inspecting the prompt.
     *
     * <p>Both jobs share one endpoint and differ only by model alias and prompt, exactly as they do
     * against the real proxy (§10.1). The discriminator is a marker the application's own prompts
     * emit, so the stub tracks the real prompt contract rather than a private convention.
     */
    private static final class ChatTransformer implements ResponseDefinitionTransformerV2 {

        @Override
        public String getName() {
            return "litellm-chat";
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            String body = serveEvent.getRequest().getBodyAsString();
            String content = isComparison(body)
                    ? comparisonJson(body)
                    : extractionJson(body);

            return ResponseDefinition.okForJson(chatEnvelope(content));
        }

        /**
         * Distinguishes the two prompts.
         *
         * <p>Matched against literal markers {@code ComparisonPrompt} emits — the "Allowed process
         * span IDs" line and the CURRENT PROCESS evidence block. The extraction prompt (§12.4) has
         * neither, because it is given standard evidence only.
         *
         * <p>These are structural sections of the prompt rather than incidental wording, so they
         * survive rewording of the instructions. If {@code ComparisonPrompt} ever drops both, this
         * stub silently answers every comparison with an extraction payload — so
         * {@code FakeLiteLlmTest} asserts the discriminator against the real prompt builder rather
         * than against a hand-written string.
         */
        private boolean isComparison(String body) {
            return body.contains("Allowed process span IDs")
                    || body.contains("CURRENT PROCESS");
        }
    }

    /**
     * OpenAI chat-completion envelope wrapping the model's JSON content.
     *
     * <p>Field set verified against a real LiteLLM response, which returns exactly
     * {@code choices, created, id, model, object, service_tier, usage}. The official
     * {@code com.openai} client that Spring AI 2.0.0 delegates to validates these, and omitting them
     * makes it reject the response with "`choices` is not set" — an error that names the one field
     * that WAS present and says nothing about the ones that were missing.
     */
    private static ChatResponse chatEnvelope(String content) {
        return new ChatResponse(
                "chatcmpl-stub",
                "chat.completion",
                // Fixed timestamp, not System.currentTimeMillis(): a stub should be byte-identical
                // across runs so a response diff means a real change.
                1767225600L,
                "docreview-comparison",
                "default",
                List.of(new Choice(0, new Message("assistant", content), "stop")),
                // Non-zero so tests can assert §10.4's token recording is wired through.
                new Usage(128, 64, 192));
    }

    /**
     * Builds an extraction response citing only spans present in the prompt.
     *
     * <p>Span IDs are harvested from the prompt rather than generated, because §12.8 rule 2 rejects
     * a requirement whose source span was not supplied — a generated ID would make every extraction
     * fail validation and the happy path would never be tested.
     */
    private static String extractionJson(String prompt) {
        List<String> spanIds = harvestSpanIds(prompt);
        if (spanIds.isEmpty()) {
            // Honest empty answer. A candidate with no resolvable spans yields no requirements, which
            // is what the real model should also do.
            return "{\"requirements\":[]}";
        }

        String control = harvest(prompt, CONTROL_ID_PATTERN, "AUTO-1");
        String spanList = quotedList(spanIds);

        // One requirement with one clause. The shape matters (span ownership, clause traceability
        // per §12.8 rule 6), not the wording.
        return """
                {"requirements":[{
                  "requirementId":"%s",
                  "parentControlId":"%s",
                  "sourceControlId":"%s",
                  "title":"Stubbed requirement %s",
                  "sourceSpanIds":[%s],
                  "normativeStrength":"MANDATORY",
                  "normalizedRequirement":"A stubbed mandatory requirement derived from %s.",
                  "atomicClauses":[{
                    "clauseId":"%s-A",
                    "subject":"responsible party",
                    "action":"perform the required control",
                    "object":"the process",
                    "timing":"before completion",
                    "condition":null,
                    "negated":false
                  }],
                  "applicability":null,
                  "expectedEvidenceHints":["documented approval","recorded timestamp"],
                  "extractionConfidence":0.91
                }]}
                """.formatted(control, control, control, control, spanList, control, control);
    }

    /**
     * Builds a comparison response citing only spans present in the prompt.
     *
     * <p>Status is derived from whether process evidence was supplied: with evidence the stub returns
     * ALIGNED, without it MISSING. That mirrors §18.3's consistency rule — ALIGNED requires supporting
     * process evidence, MISSING may have none — so the stub's output passes the real validator instead
     * of being rejected by it.
     */
    private static String comparisonJson(String prompt) {
        String requirementId = harvest(prompt, REQUIREMENT_ID_PATTERN, "AUTO-1");

        // Read the two allow-lists the prompt states explicitly rather than guessing from span
        // naming. §18.2 validates that a standard span belongs to the standard and a process span to
        // the document, so returning one in the wrong role would be rejected — and a stub whose
        // output the real validator rejects tests nothing.
        List<String> standardSpans = harvestAllowedSpans(prompt, "Allowed standard span IDs:");
        List<String> processSpans = harvestAllowedSpans(prompt, "Allowed process span IDs:");

        boolean aligned = !processSpans.isEmpty();
        String clauseId = requirementId + "-A";

        return """
                {
                  "requirementId":"%s",
                  "status":"%s",
                  "gapType":null,
                  "satisfiedClauseIds":[%s],
                  "unsatisfiedClauseIds":[%s],
                  "standardSpanIds":[%s],
                  "processSpanIds":[%s],
                  "reason":"Stubbed comparison. %s",
                  "recommendedAction":%s,
                  "confidence":0.9,
                  "needsHumanReview":false
                }
                """.formatted(
                requirementId,
                aligned ? "ALIGNED" : "MISSING",
                aligned ? "\"" + clauseId + "\"" : "",
                aligned ? "" : "\"" + clauseId + "\"",
                quotedList(standardSpans),
                quotedList(processSpans),
                aligned
                        ? "Process evidence was supplied and is treated as satisfying the requirement."
                        : "No process evidence was supplied for this requirement.",
                aligned ? "null" : "\"Document the required control in the process.\"");
    }

    // ---------------------------------------------------------------------------------------
    // Embeddings
    // ---------------------------------------------------------------------------------------

    /**
     * Answers embedding calls with one deterministic vector per input.
     *
     * <p>Returning exactly as many vectors as there were inputs is essential:
     * {@code LiteLlmEmbeddingGateway} throws on a count mismatch precisely because a short result
     * would silently shift every later vector onto the wrong chunk.
     */
    private static final class EmbeddingTransformer implements ResponseDefinitionTransformerV2 {

        @Override
        public String getName() {
            return "litellm-embeddings";
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            String body = serveEvent.getRequest().getBodyAsString();
            List<String> inputs = harvestEmbeddingInputs(body);
            if (inputs.isEmpty()) {
                inputs = List.of("");
            }

            List<EmbeddingData> data = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                data.add(new EmbeddingData("embedding", i, deterministicVector(inputs.get(i))));
            }

            return ResponseDefinition.okForJson(
                    new EmbeddingResponse("list", data, "docreview-embedding",
                            new Usage(inputs.size() * 8, 0, inputs.size() * 8)));
        }
    }

    /**
     * Derives a unit vector from the text's hash.
     *
     * <p>Deterministic so retrieval order is reproducible across runs — a random vector would make
     * every ranking assertion flaky. Normalised because Qdrant collections here use cosine distance,
     * and unnormalised vectors of varying magnitude would rank by length rather than direction.
     */
    static float[] deterministicVector(String text) {
        float[] vector = new float[DIMENSIONS];
        // A cheap stable hash per dimension: seeded from the text so identical text always maps to
        // the identical vector, and different text diverges.
        long seed = text == null ? 0 : text.hashCode();
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            // Upper bits are better distributed than lower ones in an LCG.
            float value = ((seed >>> 33) % 2000) / 1000f - 1f;
            vector[i] = value;
            norm += (double) value * value;
        }
        double length = Math.sqrt(norm);
        if (length > 0) {
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] = (float) (vector[i] / length);
            }
        }
        return vector;
    }

    // ---------------------------------------------------------------------------------------
    // Prompt scraping helpers
    // ---------------------------------------------------------------------------------------

    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("SPAN-[A-Za-z0-9_.\\-]+");
    private static final Pattern REQUIREMENT_ID_PATTERN =
            Pattern.compile("\"requirementId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CONTROL_ID_PATTERN =
            Pattern.compile("\"sourceControlId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EMBEDDING_INPUT_PATTERN =
            Pattern.compile("\"input\"\\s*:\\s*(\\[.*?])", Pattern.DOTALL);

    /** Distinct span IDs mentioned in the prompt, in first-appearance order. */
    private static List<String> harvestSpanIds(String prompt) {
        List<String> found = new ArrayList<>();
        Matcher matcher = SPAN_ID_PATTERN.matcher(prompt == null ? "" : prompt);
        while (matcher.find()) {
            String id = unescape(matcher.group());
            if (!found.contains(id)) {
                found.add(id);
            }
        }
        return found;
    }

    private static String harvest(String prompt, Pattern pattern, String fallback) {
        Matcher matcher = pattern.matcher(prompt == null ? "" : prompt);
        return matcher.find() ? unescape(matcher.group(1)) : fallback;
    }

    /**
     * Reads the span IDs listed after one of the prompt's explicit allow-list labels.
     *
     * <p>Scoped to the remainder of that LINE only. Reading further would spill into the next
     * allow-list and return process spans as standard ones — which §18.2 rejects as cross-document
     * evidence, so the bug would surface as an inexplicable validation failure.
     */
    private static List<String> harvestAllowedSpans(String prompt, String label) {
        if (prompt == null) {
            return List.of();
        }
        int start = prompt.indexOf(label);
        if (start < 0) {
            return List.of();
        }
        start += label.length();

        // The prompt writes these as a single space-separated line; \n terminates it. A serialized
        // JSON body carries the escaped form instead, so both are treated as the line end.
        int newline = prompt.indexOf('\n', start);
        int escaped = prompt.indexOf("\\n", start);
        int end = prompt.length();
        if (newline >= 0) {
            end = newline;
        }
        if (escaped >= 0 && escaped < end) {
            end = escaped;
        }

        return harvestSpanIds(prompt.substring(start, end));
    }

    /**
     * Extracts the {@code input} array of an embeddings request.
     *
     * <p>Counts elements by scanning quoted strings rather than parsing JSON: this module has no
     * Jackson dependency, and the only thing that matters is how MANY inputs there were and what
     * each contained.
     */
    private static List<String> harvestEmbeddingInputs(String body) {
        Matcher arrayMatcher = EMBEDDING_INPUT_PATTERN.matcher(body == null ? "" : body);
        if (!arrayMatcher.find()) {
            return List.of();
        }
        String array = arrayMatcher.group(1);

        List<String> inputs = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        var current = new StringBuilder();

        for (char c : array.toCharArray()) {
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                if (inString) {
                    inputs.add(current.toString());
                    current.setLength(0);
                }
                inString = !inString;
            } else if (inString) {
                current.append(c);
            }
        }
        return inputs;
    }

    /** Strips JSON escaping picked up when scraping a serialized prompt. */
    private static String unescape(String value) {
        return value.replace("\\\"", "").replace("\\n", "").trim();
    }

    private static String quotedList(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).orElse("");
    }

    // ---------------------------------------------------------------------------------------
    // Wire shapes. Records so WireMock's Jackson serializes them without a schema.
    // ---------------------------------------------------------------------------------------

    record ChatResponse(String id, String object, long created, String model,
                        String service_tier, List<Choice> choices, Usage usage) {}

    record Choice(int index, Message message, String finish_reason) {}

    record Message(String role, String content) {}

    record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {}

    record EmbeddingResponse(String object, List<EmbeddingData> data, String model, Usage usage) {}

    record EmbeddingData(String object, int index, float[] embedding) {}
}
