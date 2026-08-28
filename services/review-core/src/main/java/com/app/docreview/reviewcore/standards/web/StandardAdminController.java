package com.app.docreview.reviewcore.standards.web;

import com.app.docreview.reviewcore.standards.application.ConditionGovernanceClient;
import com.app.docreview.reviewcore.standards.application.StandardAdminService;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.CreateStandardRequest;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.StandardResponse;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.UpdateLifecycleRequest;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.VersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Standards administration API. Architecture §8.1, §33.
 *
 * <p>Class-level {@code @PreAuthorize} defaults every endpoint to write-level authority: a regular
 * user must not be able to change what everyone else is measured against. Applying it once at the
 * class avoids the failure mode where a new endpoint is added without one.
 *
 * <p>The two READ endpoints deliberately widen that to include {@code REVIEWER}, overriding the
 * class rule. §33 draws the line at modification, not visibility — a reviewer has to see which
 * standards exist in order to choose what to compare against, and the upload page's standard picker
 * is exactly that. Denying reads left the picker permanently empty with no way to start a review.
 *
 * <p>Roles are {@code STANDARDS_ADMIN} and {@code ADMIN}, matching {@link
 * com.app.docreview.reviewcore.security.Role}. An earlier version named {@code APP_ADMIN}, which
 * that enum does not define — so the expression could never match and even an administrator was
 * denied.
 */
@RestController
@RequestMapping("/api/admin/standards")
@PreAuthorize("hasAnyRole('STANDARDS_ADMIN','ADMIN')")
@RequiredArgsConstructor
public class StandardAdminController {

    private final StandardAdminService service;
    private final ConditionGovernanceClient conditionGovernance;

    /**
     * Applicability conditions for one requirement set, with extracted role, governed role and
     * provenance. Architecture §12.5, §19.2.
     *
     * <p><strong>Why an administrator needs this.</strong> Only a scope gate can make a requirement
     * NOT_APPLICABLE, and a wrongly classified one silently removes a real control from a review. This
     * is where that is auditable: which conditions a person reviewed, which the model still owns
     * unreviewed, which are authorised to exclude, and which are open questions.
     *
     * <p>Readable by a REVIEWER as well as an administrator. A reviewer who sees "scope unclear" on a
     * finding needs to be able to check why, and the alternative is asking someone with a wider role
     * to look it up for them.
     */
    /**
     * Applicability questions awaiting a policy owner. Architecture §12.5, §17.6.
     *
     * <p>Readable by a REVIEWER: someone who sees "scope unclear" on a finding needs to know the
     * question is already logged and who owns it, rather than re-raising it or quietly deciding.
     */
    @GetMapping("/policy-owner-queue")
    @PreAuthorize("hasAnyRole('REVIEWER','STANDARDS_ADMIN','ADMIN')")
    public List<ConditionGovernanceClient.OpenQuestion> policyOwnerQueue() {
        return conditionGovernance.policyOwnerQueue();
    }

    @GetMapping("/versions/{standardVersionId}/conditions")
    @PreAuthorize("hasAnyRole('REVIEWER','STANDARDS_ADMIN','ADMIN')")
    public ConditionsResponse conditions(@PathVariable String standardVersionId) {
        // The SET IS RESOLVED HERE, not passed in. A caller carrying its own requirementSetVersion is
        // carrying a value that goes stale on every re-extraction — and the failure is silent: the
        // request succeeds, returns nothing, and reads as "this standard states no conditions".
        var current = conditionGovernance.currentSet(standardVersionId);
        if (current == null || current.requirementSetVersion() == null) {
            return new ConditionsResponse(null, null, List.of());
        }
        return new ConditionsResponse(
                current.requirementSetVersion(),
                current.requirementExtractionPromptVersion(),
                conditionGovernance.conditions(standardVersionId, current.requirementSetVersion()));
    }

    /**
     * Conditions plus WHICH set they came from.
     *
     * <p>The set and prompt versions are returned rather than left implicit so the page states what it
     * is showing. An empty list is then distinguishable from an empty list for the wrong set — the
     * ambiguity that made a stale-metadata bug look like a clean review.
     */
    public record ConditionsResponse(
            String requirementSetVersion,
            String promptVersion,
            List<ConditionGovernanceClient.ConditionRow> conditions) {
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','STANDARDS_ADMIN','ADMIN')")
    public List<StandardResponse> list() {
        return service.list();
    }

    /**
     * One page of standards, searched and sorted server-side. Architecture §8.1.
     *
     * <p>Separate from {@code list()} rather than replacing it: the upload screen needs every
     * comparable standard at once to render its selection list, and paginating that would make a
     * standard on page two unselectable.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('REVIEWER','STANDARDS_ADMIN','ADMIN')")
    public com.app.docreview.contracts.page.PageResponse<StandardResponse> search(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String search,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "TITLE") String sort,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {

        // Bounded: an unbounded size lets one request pull the whole library and defeats paginating.
        return service.search(search, sort, page, Math.min(Math.max(size, 1), 100));
    }

    @GetMapping("/{standardId}")
    @PreAuthorize("hasAnyRole('REVIEWER','STANDARDS_ADMIN','ADMIN')")
    public StandardResponse get(@PathVariable String standardId) {
        return service.get(standardId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StandardResponse create(@Valid @RequestBody CreateStandardRequest request) {
        // TODO(phase-2): replace with the authenticated principal once §33 demo auth is wired.
        return service.create(request, "system");
    }

    /**
     * Publishes a new immutable version.
     *
     * <p>Multipart rather than JSON with a base64 body: standards are real documents, and base64
     * inflates them by a third while forcing the whole file through the JSON parser.
     */
    @PostMapping("/{standardId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse createVersion(@PathVariable String standardId,
                                         @RequestParam("version") String version,
                                         @RequestParam("file") MultipartFile file) {
        return service.createVersion(standardId, version, file, "system");
    }

    /**
     * Edits curated metadata, then re-projects it into the vector index. Architecture §15.1.
     *
     * <p>MongoDB is the source of truth for these labels; the Qdrant payload is rebuilt from it. Absent
     * fields are left unchanged rather than cleared, so a partial form cannot wipe curation.
     */
    @PatchMapping("/{standardId}/metadata")
    public StandardResponse updateMetadata(
            @PathVariable String standardId,
            @Valid @RequestBody StandardDtos.UpdateStandardMetadataRequest request) {
        return service.updateMetadata(standardId, request);
    }

    @PatchMapping("/versions/{versionId}/lifecycle")
    public VersionResponse updateLifecycle(@PathVariable String versionId,
                                           @Valid @RequestBody UpdateLifecycleRequest request) {
        return service.updateLifecycle(versionId, request.lifecycle());
    }
}
