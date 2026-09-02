package com.example.permissiondemo.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 신청자 본인 조회·신청과 관리자 분류·심사 API의 권한 경계를 분리한다. */
@RestController
@com.example.permissiondemo.storage.StateBoundary
public class AuthorityWorkflowController {
    private final AuthorityRequestService service;
    private final AuthorizationCatalog catalog;
    private final CurrentUserContext current;
    private final AuditEventService audit;

    public AuthorityWorkflowController(AuthorityRequestService service, AuthorizationCatalog catalog,
            CurrentUserContext current, AuditEventService audit) {
        this.service = service;
        this.catalog = catalog;
        this.current = current;
        this.audit = audit;
    }

    /** 권한 신청 후보 조회는 권한 부여와 별개이며 인증된 사용자에게만 제공한다. */
    @GetMapping("/api/me/authority-options")
    public ApiResponse<List<AuthorizationCatalog.AuthorityDefinition>> options() {
        current.require();
        return ApiResponse.ok(catalog.authorities().stream().filter(AuthorizationCatalog.AuthorityDefinition::active).toList());
    }

    @GetMapping("/api/me/authority-requests")
    public ApiResponse<PageResult<AuthorityRequestService.AuthorityRequest>> mine(
            @RequestParam(required = false) AuthorityRequestService.RequestStatus status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.list(current.require().username(), status, PageQuery.of(page, size)));
    }

    @PostMapping("/api/me/authority-requests")
    public ApiResponse<AuthorityRequestService.AuthorityRequest> submit(@Valid @RequestBody SubmitRequest request) {
        return ApiResponse.ok(service.submit(current.require().username(), request.kind(), request.authorityIds(),
                request.validFrom(), request.validTo(), request.reason()));
    }

    @GetMapping("/api/admin/authority-requests")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AuthorityRequestService.AuthorityRequest>> all(
            @RequestParam(required = false) AuthorityRequestService.RequestStatus status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.list(null, status, PageQuery.of(page, size)));
    }

    @PostMapping("/api/admin/authority-requests/review")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<List<AuthorityRequestService.AuthorityRequest>> review(@Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(service.review(current.require().username(), request.requestIds(), request.decision(), request.reason()));
    }

    @GetMapping("/api/admin/authority-classifications")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<AuthorizationCatalog.AuthorityClassification>> classifications() {
        return ApiResponse.ok(catalog.classifications());
    }

    @PostMapping("/api/admin/authority-classifications/{id}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.AuthorityClassification> saveClassification(
            @PathVariable String id, @Valid @RequestBody ClassificationRequest request) {
        var saved = catalog.saveClassification(new AuthorizationCatalog.AuthorityClassification(
                id, request.parentId(), request.name(), request.systemId(), request.active()));
        audit.record("AUTHORITY_CLASSIFICATION_SAVED", "AUTHORITY_CLASSIFICATION", saved.id(), "SUCCESS", Map.of());
        return ApiResponse.ok(saved);
    }

    @DeleteMapping("/api/admin/authority-classifications/{id}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<Void> deleteClassification(@PathVariable String id) {
        catalog.deleteClassification(id);
        audit.record("AUTHORITY_CLASSIFICATION_DELETED", "AUTHORITY_CLASSIFICATION", id, "SUCCESS", Map.of());
        return ApiResponse.ok(null);
    }

    public record SubmitRequest(@NotNull AuthorityRequestService.RequestKind kind,
            @NotEmpty @Size(max = 100) Set<@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String> authorityIds,
            @NotNull LocalDate validFrom, @NotNull LocalDate validTo, @NotBlank @Size(max = 1000) String reason) { }

    public record ReviewRequest(@NotEmpty @Size(max = 100) List<@NotNull Long> requestIds,
            @NotNull AuthorityRequestService.Decision decision, @Size(max = 1000) String reason) { }

    public record ClassificationRequest(@Pattern(regexp = "[A-Z0-9_]{1,50}") String parentId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String systemId, @NotNull Boolean active) { }
}
