package com.example.permissiondemo.web;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityClassificationImportService;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.AuthorityTermsService;
import com.example.permissiondemo.authorization.LinkedAuthorityRuleService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
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
    private final LinkedAuthorityRuleService linkedRules;
    private final DirectoryService directory;
    private final UserSsoOrganizationService userSsoOrganizations;
    private final AuthorityClassificationImportService classificationImporter;
    private final AuthorityTermsService authorityTerms;

    public AuthorityWorkflowController(AuthorityRequestService service, AuthorizationCatalog catalog,
            CurrentUserContext current, AuditEventService audit, LinkedAuthorityRuleService linkedRules,
            DirectoryService directory, UserSsoOrganizationService userSsoOrganizations,
            AuthorityClassificationImportService classificationImporter, AuthorityTermsService authorityTerms) {
        this.service = service;
        this.catalog = catalog;
        this.current = current;
        this.audit = audit;
        this.linkedRules = linkedRules;
        this.directory = directory;
        this.userSsoOrganizations = userSsoOrganizations;
        this.classificationImporter = classificationImporter;
        this.authorityTerms = authorityTerms;
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

    /** 권한 신청과 함께 수정할 수 있는 신청자 정보와 같은 사업소의 담당지역을 반환한다. */
    @GetMapping("/api/me/authority-requests/applicant")
    public ApiResponse<DirectoryService.ApplicantView> applicant() {
        return ApiResponse.ok(directory.applicant(current.require().username()));
    }

    @PostMapping("/api/me/authority-requests")
    public ApiResponse<AuthorityRequestService.AuthorityRequest> submit(@Valid @RequestBody SubmitRequest request) {
        String username = current.require().username();
        recordTermDecisions(username, request);
        updateApplicant(username, request.applicant());
        return ApiResponse.ok(service.submit(username, request.kind(), request.authorityIds(),
                request.validFrom(), request.validTo(), request.reason()));
    }

    /** 선택 권한마다 독립 신청을 원본 시스템 순서로 만들고 전체 저장은 한 상태 경계로 묶는다. */
    @PostMapping("/api/me/authority-requests/batch")
    public ApiResponse<List<AuthorityRequestService.AuthorityRequest>> submitBatch(
            @Valid @RequestBody SubmitRequest request) {
        if (request.kind() != AuthorityRequestService.RequestKind.GRANT) {
            throw new IllegalArgumentException("다중 시스템 신청은 권한 추가만 지원합니다.");
        }
        List<AuthorizationCatalog.AuthorityDefinition> ordered = request.authorityIds().stream()
                .map(authorityId -> catalog.findAuthority(authorityId)
                        .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId)))
                .sorted(Comparator.comparingInt((AuthorizationCatalog.AuthorityDefinition item) -> systemOrder(item.systemId()))
                        .thenComparing(AuthorizationCatalog.AuthorityDefinition::systemId)
                        .thenComparing(AuthorizationCatalog.AuthorityDefinition::id))
                .toList();
        String username = current.require().username();
        recordTermDecisions(username, request);
        updateApplicant(username, request.applicant());
        return ApiResponse.ok(ordered.stream()
                .map(authority -> service.submit(username, AuthorityRequestService.RequestKind.GRANT, Set.of(authority.id()),
                        request.validFrom(), request.validTo(), request.reason()))
                .toList());
    }

    /** 원본 신청 화면의 자동 추가 결과를 반환하며 실제 신청 집합은 클라이언트가 최종 선택한다. */
    @PostMapping("/api/me/authority-requests/linked-plan")
    public ApiResponse<LinkedAuthorityRuleService.LinkedPlan> linkedPlan(
            @Valid @RequestBody LinkedPlanRequest request) {
        String username = current.require().username();
        String departmentCode = userSsoOrganizations.find(username)
                .map(UserSsoOrganizationService.Affiliation::ssoDepartmentCode)
                .orElseGet(() -> directory.person(username).departmentCode());
        return ApiResponse.ok(linkedRules.plan(request.kind(), request.authorityIds(), departmentCode,
                service.satisfiedAuthorityIds(username)));
    }

    /** 최종 선택 권한의 시스템별 약관과 아직 필요한 동의 항목을 반환한다. */
    @PostMapping("/api/me/authority-requests/terms-plan")
    public ApiResponse<TermsPlanResponse> termsPlan(@Valid @RequestBody TermsPlanRequest request) {
        var plan = authorityTerms.plan(current.require().username(), request.authorityIds());
        List<String> requiredSystems = plan.terms().stream()
                .map(AuthorityTermsService.AuthorityTerm::systemId).distinct().toList();
        List<TermItemResponse> items = plan.terms().stream().map(term -> new TermItemResponse(
                term.seq(), term.systemId(), term.title(), term.body(), term.version(), term.required())).toList();
        return ApiResponse.ok(new TermsPlanResponse(requiredSystems, items, plan.unconfiguredSystems()));
    }

    @GetMapping("/api/admin/authority-requests")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AuthorityRequestService.AuthorityRequest>> all(
            @RequestParam(required = false) AuthorityRequestService.RequestStatus status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.listForReview(current.require().username(), status, PageQuery.of(page, size)));
    }

    @PostMapping("/api/admin/authority-requests/review")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<List<AuthorityRequestService.AuthorityRequest>> review(@Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(service.reviewForReviewer(current.require().username(), request.requestIds(), request.decision(), request.reason()));
    }

    @GetMapping("/api/admin/authority-classifications")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<AuthorizationCatalog.AuthorityClassification>> classifications() {
        return ApiResponse.ok(catalog.classifications());
    }

    /** 승인 추출한 원본 분류 트리와 권한 연결을 전체 검증 후 한 번에 반영한다. */
    @PostMapping("/api/admin/authority-classifications/import")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorityClassificationImportService.ImportResult> importClassifications(
            @RequestBody AuthorityClassificationImportService.ImportRequest request) {
        var result = classificationImporter.importRows(request);
        audit.record("AUTHORITY_CLASSIFICATION_IMPORTED", "AUTHORITY_CLASSIFICATION", "BATCH", "SUCCESS",
                Map.of("created", result.createdClassifications().size(),
                        "updated", result.updatedClassifications().size(),
                        "linked", result.createdAuthorityLinks().size() + result.updatedAuthorityLinks().size()));
        return ApiResponse.ok(result);
    }

    /** 승인 추출한 원본 권한 신청 약관을 전체 검증 후 일괄 반영한다. */
    @PostMapping("/api/admin/authority-terms/import")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorityTermsService.ImportResult> importTerms(
            @RequestBody AuthorityTermsService.ImportRequest request) {
        var result = authorityTerms.importTerms(request);
        audit.record("AUTHORITY_TERMS_IMPORTED", "AUTHORITY_TERMS", "BATCH", "SUCCESS",
                Map.of("created", result.created().size(), "updated", result.updated().size(), "version", result.version()));
        return ApiResponse.ok(result);
    }

    /** 승인 추출한 기존 사용자 약관 동의 이력을 원본 행 단위로 일괄 반영한다. */
    @PostMapping("/api/admin/authority-term-agreements/import")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorityTermsService.AgreementImportResult> importTermAgreements(
            @RequestBody AuthorityTermsService.AgreementImportRequest request) {
        var result = authorityTerms.importAgreements(request);
        audit.record("AUTHORITY_TERM_AGREEMENTS_IMPORTED", "AUTHORITY_TERMS", "AGREEMENT_BATCH", "SUCCESS",
                Map.of("created", result.created(), "unchanged", result.unchanged(), "version", result.version()));
        return ApiResponse.ok(result);
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
            LocalDate validFrom, LocalDate validTo, @NotBlank @Size(max = 1000) String reason,
            List<TermDecisionRequest> termDecisions, @Valid ApplicantRequest applicant) { }

    public record ApplicantRequest(
            @NotBlank @Size(max = 50) String rankCode,
            @NotBlank @Pattern(regexp = "[0-9]{1,15}") String telephone,
            @Pattern(regexp = "(?:|010[0-9]{8})") String mobile,
            @NotBlank @Size(max = 150)
                    @Pattern(regexp = "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}") String email,
            @NotBlank @Size(max = 1000) String jobDescription,
            @NotNull @Size(max = 500) Set<@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,50}") String> regionIds) { }

    public record LinkedPlanRequest(@NotNull AuthorityRequestService.RequestKind kind,
            @NotEmpty @Size(max = 100) Set<@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String> authorityIds) { }

    public record TermsPlanRequest(
            @NotEmpty @Size(max = 100) Set<@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String> authorityIds) { }

    public record TermDecisionRequest(long termSequence, boolean agreed) { }

    public record TermItemResponse(
            long termSequence, String systemId, String title, String body, String version, boolean required) { }

    public record TermsPlanResponse(
            List<String> requiredSystems, List<TermItemResponse> items, Set<String> unconfiguredSystems) { }

    public record ReviewRequest(@NotEmpty @Size(max = 100) List<@NotNull Long> requestIds,
            @NotNull AuthorityRequestService.Decision decision, @Size(max = 1000) String reason) { }

    public record ClassificationRequest(@Pattern(regexp = "[A-Z0-9_]{1,50}") String parentId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String systemId, @NotNull Boolean active) { }

    private static int systemOrder(String systemId) {
        return switch (systemId) {
            case "AI" -> 0;
            case "CY" -> 1;
            case "MO" -> 2;
            case "AB" -> 3;
            default -> 4;
        };
    }

    private void recordTermDecisions(String username, SubmitRequest request) {
        List<AuthorityTermsService.AgreementDecision> decisions = request.termDecisions() == null ? List.of()
                : request.termDecisions().stream()
                        .map(item -> new AuthorityTermsService.AgreementDecision(item.termSequence(), item.agreed()))
                        .toList();
        List<AuthorityTermsService.Agreement> recorded = authorityTerms.recordDecisions(
                username, request.authorityIds(), decisions);
        if (!recorded.isEmpty()) {
            audit.record("AUTHORITY_TERMS_DECIDED", "AUTHORITY_TERMS", "APPLICATION", "SUCCESS",
                    Map.of("count", recorded.size(), "agreed", recorded.stream().filter(AuthorityTermsService.Agreement::agreed).count()));
        }
    }

    private void updateApplicant(String username, ApplicantRequest applicant) {
        if (applicant == null) return;
        directory.updateApplicant(username, new DirectoryService.ApplicantUpdate(
                applicant.rankCode(), applicant.telephone(), applicant.mobile(), applicant.email(),
                applicant.jobDescription(), applicant.regionIds()));
    }
}
