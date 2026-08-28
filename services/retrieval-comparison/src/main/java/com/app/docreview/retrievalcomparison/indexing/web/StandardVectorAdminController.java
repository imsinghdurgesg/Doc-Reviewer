package com.app.docreview.retrievalcomparison.indexing.web;

import com.app.docreview.retrievalcomparison.indexing.ReembedService;
import com.app.docreview.retrievalcomparison.indexing.VectorIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Removes a standard version's vectors. Architecture §8.1, §15.1.
 *
 * <p><strong>Why an endpoint exists at all.</strong> Nothing deleted standard vectors, so a version
 * that was removed or replaced kept its profile vector — and that vector stays searchable, so it comes
 * back as a candidate for comparison. On a live index this produced 37 profile vectors against 33 live
 * versions, and one candidate lookup returned fifteen near-identical copies of a single standard.
 * Selection then chooses between duplicates rather than between different standards.
 *
 * <p><strong>Deliberately not automatic on archival.</strong> An ARCHIVED version must remain
 * retrievable, because §19.2 requires a historical comparison to be reproducible against the standard
 * text it actually ran against. Deleting vectors when a version is archived would silently break that.
 * So removal is an explicit operation for versions that no longer exist, not a lifecycle side effect.
 *
 * <p>Internal-only: reached by an operator or a cleanup task through the internal token, never by a
 * browser (§3, §33).
 */
@RestController
@RequestMapping("/internal/v1/standard-vectors")
@RequiredArgsConstructor
@Slf4j
public class StandardVectorAdminController {

    private final VectorIndexingService indexingService;
    private final ReembedService reembedService;

    /**
     * Deletes the profile vector and every requirement vector for one version.
     *
     * <p>Idempotent: deleting vectors that are already gone succeeds. A cleanup that failed halfway
     * must be safe to re-run, and a caller reconciling an index against a database cannot know in
     * advance which versions still have vectors.
     */
    @DeleteMapping("/{standardVersionId}")
    public Map<String, Object> delete(@PathVariable String standardVersionId) {
        indexingService.deleteStandardVectors(standardVersionId);
        return Map.of("standardVersionId", standardVersionId, "deleted", true);
    }

    /**
     * Rebuilds one version's vectors from its already-extracted requirements.
     *
     * <p>No model call: extraction is not re-run and profile metadata is not re-derived. Used after a
     * change that invalidates vectors but not requirements — a new chunk taxonomy, a new embedding
     * model, a new payload field. Re-running the full indexing job instead would re-extract hundreds of
     * requirements to arrive at text already on disk (§12.1).
     *
     * <p>Metadata travels in the request because review-core owns the standard record; deriving it here
     * would both duplicate that ownership and risk changing the category, which would move the profile
     * vector for reasons unrelated to the re-embed.
     */
    @PostMapping("/{standardVersionId}/reembed")
    public ReembedService.Result reembed(@PathVariable String standardVersionId,
                                         @RequestBody ReembedRequest request) {
        return reembedService.reembed(
                request.standardId(), standardVersionId,
                blankToNull(request.title()), blankToNull(request.description()),
                new VectorIndexingService.StandardMetadata(
                        // Blank-to-null throughout: review-core sends "" for absent labels because
                        // Map.of rejects nulls, and storing "" would make an unlabelled standard look
                        // labelled — then AssessmentContext would compare against it and score a
                        // mismatch against every real value.
                        blankToNull(request.department()), blankToNull(request.category()),
                        blankToNull(request.standardFamily()), blankToNull(request.documentType()),
                        request.tags() == null ? List.of() : request.tags(),
                        blankToNull(request.standardKey()), blankToNull(request.version()),
                        blankToNull(request.lifecycle())));
    }

    /**
     * @param tags      may be null; treated as empty rather than rejected, since tags are optional
     * @param lifecycle needed so candidate grouping can pick a default version WITHOUT parsing version
     *                  labels, which have no reliable ordering (§8.1)
     */
    public record ReembedRequest(
            String standardId,
            String title,
            String description,
            String department,
            String category,
            String standardFamily,
            String documentType,
            List<String> tags,
            String standardKey,
            String version,
            String lifecycle) {
    }

    /** review-core sends "" for absent values; "" must never be stored as a label. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
