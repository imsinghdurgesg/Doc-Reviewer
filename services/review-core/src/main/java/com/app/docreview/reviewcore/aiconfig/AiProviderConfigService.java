package com.app.docreview.reviewcore.aiconfig;

import com.app.docreview.reviewcore.shared.error.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads and updates the model provider configuration. Architecture §10, §30.
 *
 * <p>Two responsibilities that have to stay together: the stored configuration, and the connection
 * test that proves it works. Saving settings that were never exercised is how an administrator
 * discovers a wrong URL at the moment a reviewer runs a comparison.
 */
@Service
@Slf4j
public class AiProviderConfigService {

    private final AiProviderConfigRepository repository;
    private final SecretCipher cipher;

    /** Environment fallback, used when nothing has been configured through Admin (§30). */
    private final String envBaseUrl;
    private final String envApiKey;

    public AiProviderConfigService(AiProviderConfigRepository repository,
                                   SecretCipher cipher,
                                   @Value("${docreview.ai.base-url:}") String envBaseUrl,
                                   @Value("${docreview.ai.api-key:}") String envApiKey) {
        this.repository = repository;
        this.cipher = cipher;
        this.envBaseUrl = envBaseUrl;
        this.envApiKey = envApiKey;
    }

    /** The stored configuration, or an empty one on first use. */
    public AiProviderConfig current() {
        return repository.findById(AiProviderConfig.SINGLETON_ID).orElseGet(AiProviderConfig::empty);
    }

    /**
     * Applies an update.
     *
     * <p><strong>A blank key means "keep the existing one".</strong> The Admin form cannot show the
     * current key — it is never returned to the browser — so an administrator editing the model name
     * would otherwise wipe the credential simply by leaving the field as they found it.
     */
    public AiProviderConfig update(AiProviderConfigUpdate update, String actor) {
        var config = current();

        if (update.apiKey() != null && !update.apiKey().isBlank()) {
            if (!cipher.isConfigured()) {
                throw ApiException.badRequest("SECRET_KEY_MISSING",
                        "DOCREVIEW_SECRET_KEY is not set on the server, so an API key cannot be "
                                + "stored. Generate one with `openssl rand -base64 32` and restart.");
            }
            config.setEncryptedApiKey(cipher.encrypt(update.apiKey().trim()));
        }

        if (update.provider() != null) {
            config.setProvider(update.provider());
        }
        if (update.baseUrl() != null) {
            // Trailing slashes are stripped: the client appends `/v1/...`, and a stored
            // "https://host/" produces "https://host//v1/..." which some gateways 404.
            config.setBaseUrl(update.baseUrl().trim().replaceAll("/+$", ""));
        }
        if (update.comparisonModel() != null) {
            config.setComparisonModel(update.comparisonModel().trim());
        }
        if (update.extractionModel() != null) {
            config.setExtractionModel(update.extractionModel().trim());
        }
        if (update.embeddingModel() != null) {
            config.setEmbeddingModel(update.embeddingModel().trim());
        }
        if (update.comparisonInstructions() != null) {
            config.setComparisonInstructions(update.comparisonInstructions());
        }
        if (update.extractionInstructions() != null) {
            config.setExtractionInstructions(update.extractionInstructions());
        }
        if (update.profileInstructions() != null) {
            config.setProfileInstructions(update.profileInstructions());
        }

        // The previous test verdict no longer describes this configuration, so it is DISCARDED
        // rather than carried forward.
        //
        // Keeping it made a successful save look like a failure: testing before entering a server URL
        // stored "No server URL is configured.", and the save response then returned that message
        // alongside the settings it had just accepted. The administrator sees an error naming a field
        // they have demonstrably filled in, whose real subject is a test that ran a minute earlier.
        //
        // Cleared, not recomputed: this method must not make a network call, or saving a typo'd URL
        // would block on a provider timeout.
        config.setLastTestOk(null);
        config.setLastTestMessage(null);
        config.setLastTestedAt(null);

        config.setUpdatedAt(Instant.now());
        config.setUpdatedBy(actor);

        var saved = repository.save(config);
        // Never the key, never the URL's credentials — actor and model names only (§35).
        log.info("AI provider configuration updated by {} (provider={}, comparisonModel={})",
                actor, saved.getProvider(), saved.getComparisonModel());
        return saved;
    }

    /**
     * Calls the configured provider and records the outcome.
     *
     * <p>Uses {@code /models}, the cheapest authenticated endpoint an OpenAI-compatible server
     * offers. A chat completion would also prove connectivity but costs tokens and can fail for
     * reasons unrelated to configuration — a rate limit or a bad model alias — which makes the test
     * report a working setup as broken.
     */
    public AiProviderConfig test(String actor) {
        var config = current();
        String baseUrl = effectiveBaseUrl(config);

        if (baseUrl == null || baseUrl.isBlank()) {
            return recordTest(config, false, "No server URL is configured.");
        }

        try {
            var client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(timeoutFactory())
                    .build();

            var request = client.get().uri("/v1/models");
            String apiKey = effectiveApiKey(config);
            if (apiKey != null && !apiKey.isBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            var body = request.retrieve().body(Map.class);

            @SuppressWarnings("unchecked")
            var data = body == null ? null : (List<Map<String, Object>>) body.get("data");
            int count = data == null ? 0 : data.size();

            return recordTest(config, true,
                    "Connected. The gateway reports " + count + " model(s).");

        } catch (Exception e) {
            // Class name only. A provider error body can echo the Authorization header back (§35).
            log.warn("Provider connection test failed: {}", e.getClass().getSimpleName());
            return recordTest(config, false,
                    "Could not reach the provider (" + e.getClass().getSimpleName()
                            + "). Check the server URL and API key.");
        }
    }

    private AiProviderConfig recordTest(AiProviderConfig config, boolean ok, String message) {
        config.setLastTestOk(ok);
        config.setLastTestMessage(message);
        config.setLastTestedAt(Instant.now());
        return repository.save(config);
    }

    /** Admin value when set, otherwise the environment default. */
    public String effectiveBaseUrl(AiProviderConfig config) {
        return config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl()
                : envBaseUrl;
    }

    /**
     * The decrypted key, or the environment one.
     *
     * <p>Kept package-visible rather than exposed on the controller: the only legitimate consumers
     * are this service's connection test and the worker that dispatches a comparison. No endpoint
     * returns it.
     */
    String effectiveApiKey(AiProviderConfig config) {
        if (config.hasApiKey()) {
            return cipher.decrypt(config.getEncryptedApiKey());
        }
        return envApiKey;
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Short: this is a reachability check an administrator is waiting on, and a gateway that
        // takes longer than this to list models is not usable for a comparison either.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /** Fields an administrator may change. Null means "leave unchanged". */
    public record AiProviderConfigUpdate(
            String provider,
            String baseUrl,
            String apiKey,
            String comparisonModel,
            String extractionModel,
            String embeddingModel,
            String comparisonInstructions,
            String extractionInstructions,
            String profileInstructions
    ) {
    }
}
