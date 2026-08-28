package com.app.docreview.retrievalcomparison.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The administered configuration is the ONLY source of a provider. Architecture §9.2, §10, §35.
 *
 * <p>These cases exist because of a real failure: this service resolved review-core's address from a
 * property that was never defined, so the fetch 404'd, fell back to the environment, and — with no
 * environment value — let the OpenAI client use its own default endpoint. A comparison that should
 * have called an internal gateway was aimed at api.openai.com and failed reporting a missing API
 * key, which pointed at the credential rather than the actual misconfiguration.
 *
 * <p>What is pinned here is the SAFETY property: an unresolved provider must fail, never silently
 * become a different provider.
 */
class RuntimeAiConfigTest {

    @Nested
    @DisplayName("requireBaseUrl")
    class RequireBaseUrl {

        @Test
        @DisplayName("returns the administered provider root")
        void returnsConfiguredUrl() {
            var config = new RuntimeAiConfig(
                    "https://gateway.example.org", "sk-test", null, null, null, null, null, null);

            assertThat(config.requireBaseUrl()).isEqualTo("https://gateway.example.org");
        }

        @Test
        @DisplayName("fails when absent, rather than yielding a default endpoint")
        void failsWhenNull() {
            // The whole point: no default. A null here previously reached the provider's own
            // default host with document text attached.
            assertThatThrownBy(() -> RuntimeAiConfig.empty().requireBaseUrl())
                    .isInstanceOf(AiConfigUnavailableException.class)
                    .hasMessageContaining("Admin -> AI & matching");
        }

        @Test
        @DisplayName("treats blank as absent")
        void failsWhenBlank() {
            // A cleared Admin field arrives as "" or "   ", not null.
            var config = new RuntimeAiConfig("   ", "sk-test", null, null, null, null, null, null);

            assertThatThrownBy(config::requireBaseUrl)
                    .isInstanceOf(AiConfigUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("requireApiKey")
    class RequireApiKey {

        @Test
        @DisplayName("returns the administered key")
        void returnsConfiguredKey() {
            var config = new RuntimeAiConfig("https://gateway.example.org", "sk-test",
                    null, null, null, null, null, null);

            assertThat(config.requireApiKey()).isEqualTo("sk-test");
        }

        @Test
        @DisplayName("fails when absent")
        void failsWhenNull() {
            assertThatThrownBy(() -> RuntimeAiConfig.empty().requireApiKey())
                    .isInstanceOf(AiConfigUnavailableException.class)
                    .hasMessageContaining("Admin -> AI & matching");
        }

        @Test
        @DisplayName("does not put the key in the failure message")
        void neverLeaksKeyInMessage() {
            // §35: this message reaches logs and a job's failure reason.
            var thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> RuntimeAiConfig.empty().requireApiKey());

            assertThat(thrown).hasMessageNotContainingAny("sk-", "Bearer");
        }
    }

    @Nested
    @DisplayName("pick")
    class Pick {

        @Test
        @DisplayName("prefers the administered value over the fallback")
        void prefersOverride() {
            assertThat(RuntimeAiConfig.pick("admin-alias", "env-alias")).isEqualTo("admin-alias");
        }

        @Test
        @DisplayName("falls back when the override is absent or blank")
        void fallsBack() {
            // Aliases legitimately fall back: they are indirection names, not credentials, so a
            // deployment default is a sane answer where a provider default is not.
            assertThat(RuntimeAiConfig.pick(null, "env-alias")).isEqualTo("env-alias");
            assertThat(RuntimeAiConfig.pick("  ", "env-alias")).isEqualTo("env-alias");
        }
    }
}
