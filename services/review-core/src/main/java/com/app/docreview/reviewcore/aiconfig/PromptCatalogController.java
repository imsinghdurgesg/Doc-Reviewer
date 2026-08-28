package com.app.docreview.reviewcore.aiconfig;

import com.app.docreview.reviewcore.workflow.InternalRestClientFactory;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Shows administrators the prompts the models are actually given. Architecture §6, §10.4, §33.
 *
 * <p><strong>Why this is worth exposing.</strong> An administrator can already append guidance to the
 * comparison prompt, but could not see what they were appending to — so there was no way to tell what
 * the model is already instructed to do, or whether new guidance would conflict with it. That made a
 * live tuning surface guesswork.
 *
 * <p><strong>Read-only, and that is the design.</strong> The prompts carry the output contract that
 * {@code ComparisonOutputValidator} enforces — span IDs only, a bounded status set, the anti-injection
 * nonce. Editing those does not degrade results gradually; every requirement fails validation and the
 * reviewer sees "could not be evaluated" with nothing pointing at the cause. Guidance is the editable
 * surface, and it is appended BELOW the contract with the contract winning on conflict.
 *
 * <p>Proxied from retrieval-comparison rather than duplicated here: §6 forbids depending on another
 * service's jar, and a copy would drift from the text actually being sent.
 */
@RestController
@RequestMapping("/api/admin/prompts")
@PreAuthorize("hasAnyRole('STANDARDS_ADMIN','ADMIN')")
@Slf4j
public class PromptCatalogController {

    private final RestClient retrievalComparison;

    public PromptCatalogController(JobDispatchProperties properties,
                                   InternalRestClientFactory clientFactory) {
        this.retrievalComparison =
                clientFactory.create(properties.retrievalComparison().baseUrl());
    }

    /**
     * The prompt catalogue, or an empty list when the worker cannot be reached.
     *
     * <p>Empty rather than an error: this screen is informational, and a worker restart should not
     * make the Admin section look broken. The UI states that prompts are unavailable.
     */
    @GetMapping
    public List<Map<String, Object>> prompts() {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = retrievalComparison.get()
                    .uri("/internal/v1/prompts")
                    .retrieve()
                    .body(List.class);
            return body == null ? List.of() : body;
        } catch (Exception e) {
            // Class name only (§35).
            log.warn("Could not read the prompt catalogue from retrieval-comparison ({})",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }
}
