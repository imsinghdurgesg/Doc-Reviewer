package com.app.docreview.reviewcore.aiconfig;

import com.app.docreview.reviewcore.aiconfig.AiProviderConfigService.AiProviderConfigUpdate;
import com.app.docreview.reviewcore.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Model provider settings. Architecture §10, §30.
 *
 * <p>Restricted to administrators: this decides which model every comparison in the deployment runs
 * against, and holds the credential that pays for it.
 *
 * <p><strong>The API key is never in a response.</strong> {@link AiProviderConfigResponse} reports
 * {@code apiKeySet} — a boolean — and nothing more. Returning the key so the form could pre-fill it
 * would put a live credential in every browser that opens the Admin screen, in memory, in the
 * network log, and in any error report that captures a response body.
 */
@RestController
@RequestMapping("/api/admin/ai-config")
@PreAuthorize("hasAnyRole('STANDARDS_ADMIN','ADMIN')")
@RequiredArgsConstructor
public class AiProviderConfigController {

    private final AiProviderConfigService service;
    private final SecretCipher cipher;
    private final CurrentUser currentUser;

    /**
     * @param apiKeySet    whether a key is stored — the only thing the browser learns about it
     * @param secretKeyReady whether the server can store one at all. False means
     *                       {@code DOCREVIEW_SECRET_KEY} is unset, and the form should say so rather
     *                       than letting an administrator type a key that will be rejected on save
     */
    public record AiProviderConfigResponse(
            String provider,
            String baseUrl,
            boolean apiKeySet,
            boolean secretKeyReady,
            String comparisonModel,
            String extractionModel,
            String embeddingModel,
            String comparisonInstructions,
            String extractionInstructions,
            String profileInstructions,
            Instant updatedAt,
            String updatedBy,
            Boolean lastTestOk,
            String lastTestMessage,
            Instant lastTestedAt) {
    }

    @GetMapping
    public AiProviderConfigResponse get() {
        return toResponse(service.current());
    }

    /** A blank {@code apiKey} keeps the stored one; see {@code AiProviderConfigService.update}. */
    @PutMapping
    public AiProviderConfigResponse update(@RequestBody AiProviderConfigUpdate body) {
        return toResponse(service.update(body, currentUser.id()));
    }

    /** Calls the provider and records whether it answered. */
    @PostMapping("/test")
    public AiProviderConfigResponse test() {
        return toResponse(service.test(currentUser.id()));
    }

    private AiProviderConfigResponse toResponse(AiProviderConfig config) {
        return new AiProviderConfigResponse(
                config.getProvider(),
                service.effectiveBaseUrl(config),
                config.hasApiKey(),
                cipher.isConfigured(),
                config.getComparisonModel(),
                config.getExtractionModel(),
                config.getEmbeddingModel(),
                config.getComparisonInstructions(),
                config.getExtractionInstructions(),
                config.getProfileInstructions(),
                config.getUpdatedAt(),
                config.getUpdatedBy(),
                config.getLastTestOk(),
                config.getLastTestMessage(),
                config.getLastTestedAt());
    }
}
