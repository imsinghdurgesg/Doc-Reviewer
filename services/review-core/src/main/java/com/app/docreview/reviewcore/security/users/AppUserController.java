package com.app.docreview.reviewcore.security.users;

import com.app.docreview.contracts.page.PageResponse;
import com.app.docreview.reviewcore.security.CurrentUser;
import com.app.docreview.reviewcore.security.Role;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Managing who may sign in. Architecture §33.
 *
 * <p><strong>ADMIN only, not STANDARDS_ADMIN.</strong> Curating the standards library and deciding who
 * may see the organization's reviews are different authorities, and conflating them would silently
 * widen this boundary the next time someone is given the standards role.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AppUserController {

    private final AppUserService users;
    private final CurrentUser currentUser;

    /**
     * @param self true for the caller's own row, so the UI can disable the controls that would lock
     *             them out rather than letting them click and receive an error
     */
    public record UserResponse(
            String email,
            String displayName,
            List<String> roles,
            String status,
            Instant lastLoginAt,
            Instant updatedAt,
            String updatedBy,
            boolean self) {
    }

    public record InviteRequest(@NotBlank String email, Set<Role> roles) {
    }

    public record RolesRequest(Set<Role> roles) {
    }

    public record StatusRequest(AppUser.Status status) {
    }

    @GetMapping
    public PageResponse<UserResponse> search(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var found = users.search(search, page, size);
        return new PageResponse<>(
                found.getContent().stream().map(this::toResponse).toList(),
                found.getTotalElements(),
                found.getNumber(),
                found.getSize(),
                found.getTotalPages(),
                // Exact: unlike the comparison search, nothing is filtered after the fetch.
                true);
    }

    @PostMapping
    public UserResponse invite(@RequestBody InviteRequest request) {
        return toResponse(users.invite(request.email(), request.roles(), currentUser.id()));
    }

    /** Email in the path is URL-encoded by the caller; Spring decodes it. */
    @PutMapping("/{email}/roles")
    public UserResponse updateRoles(@PathVariable String email, @RequestBody RolesRequest request) {
        return toResponse(users.updateRoles(email, request.roles(), currentUser.id()));
    }

    @PutMapping("/{email}/status")
    public UserResponse updateStatus(@PathVariable String email, @RequestBody StatusRequest request) {
        return toResponse(users.setStatus(email, request.status(), currentUser.id()));
    }

    private UserResponse toResponse(AppUser user) {
        return new UserResponse(
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles().stream().map(Role::name).toList(),
                user.getStatus().name(),
                user.getLastLoginAt(),
                user.getUpdatedAt(),
                user.getUpdatedBy(),
                user.getEmail().equals(AppUser.normalise(currentUser.id())));
    }
}
