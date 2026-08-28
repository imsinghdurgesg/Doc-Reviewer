package com.app.docreview.retrievalcomparison.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address used to fetch the administered configuration must be declared and env-driven.
 * Architecture §9.2, §20.3.
 *
 * <p>This pins a defect no unit test could have caught. {@code RuntimeAiConfigProvider} read
 * {@code docreview.jobs.callback-base-url}, a property defined NOWHERE — not in this service's yml,
 * not in compose — so it always fell through to its {@code @Value} default of {@code localhost:8080}.
 * Inside a container that is this service, so the request hit our own port, 404'd, and silently
 * degraded to environment configuration. With no environment value that meant the OpenAI client used
 * its own default endpoint.
 *
 * <p><strong>Why this asserts on the file rather than a Spring context.</strong> The bug was an
 * ABSENT property. A context test needs this service's real infrastructure to start, and mocking that
 * away removes the property resolution being verified. Reading the resource keeps the assertion on
 * the one fact that matters and makes the test honest about its scope.
 */
class AiConfigWiringTest {

    private static String applicationYml() throws IOException {
        try (var in = new org.springframework.core.io.ClassPathResource("application.yml")
                .getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("callback-base-url is declared and reads REVIEW_CORE_BASE_URL")
    void declaredAndEnvDriven() throws IOException {
        var yml = applicationYml();

        assertThat(yml)
                .as("the property RuntimeAiConfigProvider reads must exist, or it silently "
                        + "defaults to localhost — which in a container is this service")
                .contains("callback-base-url:");

        assertThat(yml)
                .as("it must be settable per environment so Compose can point it at review-core")
                .containsPattern("callback-base-url:\\s*\\$\\{REVIEW_CORE_BASE_URL");
    }

    @Test
    @DisplayName("the provider base URL has no built-in default")
    void noProviderDefault() throws IOException {
        var yml = applicationYml();

        // A default here is what let a misconfiguration reach an unintended provider. `${VAR:}` is
        // required; `${VAR:http://...}` is the bug.
        //
        // This property IS load-bearing for embeddings: OpenAiEmbeddingModel binds its client at
        // construction, so unlike the chat gateways it cannot be repointed per request. A wrong
        // value here is therefore not merely a fallback — it is where every embedding call goes.
        assertThat(yml)
                .as("spring.ai.openai.base-url must default to blank, so an unconfigured "
                        + "provider fails loudly instead of resolving somewhere unintended")
                .containsPattern("base-url:\\s*\\$\\{LITELLM_BASE_URL:\\}");
    }

    @Test
    @DisplayName("embedding options carry no base URL, which would be silently ignored")
    void embeddingOptionsCarryNoProvider() throws IOException {
        // Regression guard. Setting .baseUrl()/.apiKey() on OpenAiEmbeddingOptions compiles and runs
        // but has no effect: the model reads them from its OWN constructor options. Code that sets
        // them reads as working configuration while calls still go to the bean's provider.
        // Read from the module root rather than the classpath: the file is a source, not a resource.
        // Asserting it EXISTS matters — an assumption here would let the test skip silently and
        // report success while checking nothing.
        var gateway = java.nio.file.Path.of("src/main/java/com/app/docreview/retrievalcomparison"
                + "/llm/litellm/LiteLlmEmbeddingGateway.java");

        assertThat(gateway)
                .as("test must run from the module root so it can read the gateway source")
                .exists();

        var source = java.nio.file.Files.readString(gateway, StandardCharsets.UTF_8);

        // Only the executable builder chain, with comments stripped: the explanation of this very
        // hazard mentions .baseUrl()/.apiKey() in prose, and matching that would fail the test for
        // documenting the problem.
        var code = source.substring(source.indexOf("private OpenAiEmbeddingOptions"))
                .lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("//") && !line.startsWith("*")
                        && !line.startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(code)
                .as("OpenAiEmbeddingOptions ignores baseUrl/apiKey; setting them hides where "
                        + "embedding calls actually go")
                .doesNotContain(".baseUrl(")
                .doesNotContain(".apiKey(");
    }
}
