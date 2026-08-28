package com.app.docreview.retrievalcomparison.llm.web;

import com.app.docreview.retrievalcomparison.comparison.prompt.ComparisonPrompt;
import com.app.docreview.retrievalcomparison.extraction.prompt.ExtractionPrompt;
import com.app.docreview.retrievalcomparison.extraction.prompt.StandardProfilePrompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the prompt templates for display. Architecture §6, §10.4, §19.2.
 *
 * <p><strong>Why an endpoint rather than a shared class.</strong> The prompts live here, with the
 * gateways that use them, but the Admin screen is served by review-core — and §6 forbids one service
 * depending on another's jar. So they travel over the internal API, which also means the displayed
 * text is always what THIS service would actually send, not a copy that can drift.
 *
 * <p><strong>Templates, never filled prompts.</strong> The nonce placeholder is literal and no
 * document text is substituted. A filled prompt would carry standard and process content, and §35
 * keeps document bodies out of anything logged or displayed outside the review workspace. The point of
 * showing these is to explain what the model is told, which the template does completely.
 *
 * <p>Under {@code /internal/**}, so the shared-token filter guards it and §3 keeps it off the
 * browser-facing surface. review-core re-exposes it to administrators.
 */
@RestController
@RequestMapping("/internal/v1/prompts")
public class PromptCatalogController {

    /** A literal stand-in for the per-request nonce, so the delimiters read correctly. */
    private static final String NONCE_PLACEHOLDER = "<nonce>";

    /**
     * @param key          stable identifier, for the UI to key an editable guidance field against
     * @param name         reader-facing label
     * @param version      the version recorded on every result produced with it (§19.2)
     * @param purpose      one line on what this prompt decides
     * @param systemPrompt the locked contract, as it will be sent
     * @param guidanceKey  the Admin field appended to this prompt, or null when it takes none yet
     */
    public record PromptView(
            String key,
            String name,
            String version,
            String purpose,
            String systemPrompt,
            String guidanceKey) {
    }

    @GetMapping
    public List<PromptView> prompts() {
        return List.of(
                new PromptView(
                        "comparison",
                        "Comparison",
                        ComparisonPrompt.PROMPT_VERSION,
                        "Decides whether the uploaded document satisfies one requirement, and which "
                                + "passages support that verdict.",
                        // WITHOUT guidance: the screen shows the locked contract, and the
                        // administrator's own text is displayed separately in its own editor. Merging
                        // them would make it impossible to see which part cannot be changed.
                        ComparisonPrompt.system(NONCE_PLACEHOLDER),
                        "comparisonInstructions"),
                new PromptView(
                        "extraction",
                        "Requirement extraction",
                        ExtractionPrompt.PROMPT_VERSION,
                        "Turns a standard's clauses into discrete, individually checkable "
                                + "requirements.",
                        ExtractionPrompt.system(NONCE_PLACEHOLDER),
                        "extractionInstructions"),
                new PromptView(
                        "profile",
                        "Standard profile",
                        StandardProfilePrompt.PROMPT_VERSION,
                        "Summarises what a standard governs, so uploaded documents are matched to "
                                + "the right one.",
                        StandardProfilePrompt.system(NONCE_PLACEHOLDER),
                        "profileInstructions"));
    }
}
