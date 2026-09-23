package com.example.permissiondemo.web;

import java.time.LocalDate;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestModels;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.SortDirection;
import com.example.permissiondemo.security.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 자기 권한 신청과 관리자 승인 큐 API를 제공한다. */
@RestController
@RequestMapping("/api")
public class AuthorityWorkflowController {

    private final AuthorityRequestService service;
    private final CurrentUserContext userContext;
    private final AuthorizationCatalog catalog;

    public AuthorityWorkflowController(
            AuthorityRequestService service,
            CurrentUserContext userContext,
            AuthorizationCatalog catalog) {
        this.service = service;
        this.userContext = userContext;
        this.catalog = catalog;
    }

    /** 자기 권한 신청 폼에 노출할 활성 권한과 부여 유형을 반환한다. */
    @GetMapping("/authority-requests/options")
    public ApiResponse<RequestOptions> options() {
        return ApiResponse.ok(new RequestOptions(
                catalog.authorities().stream().filter(AuthorizationCatalog.AuthorityDefinition::active).toList(),
                java.util.List.of(AuthorizationCatalog.AssignmentType.values())));
    }

    @PostMapping("/authority-requests")
    public ApiResponse<AuthorityRequestModels.AuthorityRequest> submit(
            @Valid @RequestBody SubmitRequest request) {
        return ApiResponse.ok(service.submit(
                userContext.require().username(),
                new AuthorityRequestModels.SubmitCommand(
                        request.authorityId(), request.assignmentType(),
                        request.validFrom(), request.validTo(),
                        request.delegatedBy(), request.reason())));
    }

    @GetMapping("/authority-requests/mine")
    public ApiResponse<PageResult<AuthorityRequestModels.AuthorityRequest>> mine(
            @RequestParam(required = false) AuthorityRequestModels.RequestStatus status,
            @RequestParam(required = false) String authorityId,
            @RequestParam(required = false) AuthorityRequestModels.RequestSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.findMine(
                userContext.require().username(), status, authorityId, sort, direction,
                PageQuery.of(page, size)));
    }

    @PostMapping("/authority-requests/{requestId}/cancel")
    public ApiResponse<AuthorityRequestModels.AuthorityRequest> cancel(
            @PathVariable long requestId,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(service.cancel(
                requestId, request.version(), userContext.require().username(), request.reason()));
    }

    @GetMapping("/admin/authority-requests")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH_REQUEST', 'AUTHORITY', 'AUTHORITY_REQUEST_READ')")
    public ApiResponse<PageResult<AuthorityRequestModels.AuthorityRequest>> requests(
            @RequestParam(required = false) AuthorityRequestModels.RequestStatus status,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String authorityId,
            @RequestParam(required = false) AuthorityRequestModels.RequestSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.findAll(
                status, username, authorityId, sort, direction, PageQuery.of(page, size)));
    }

    @PostMapping("/admin/authority-requests/{requestId}/approve")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH_REQUEST', 'AUTHORITY', 'AUTHORITY_REQUEST_APPROVE')")
    public ApiResponse<AuthorityRequestModels.AuthorityRequest> approve(
            @PathVariable long requestId,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(service.approve(
                requestId, request.version(), userContext.require().username(), request.reason()));
    }

    @PostMapping("/admin/authority-requests/{requestId}/reject")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH_REQUEST', 'AUTHORITY', 'AUTHORITY_REQUEST_REJECT')")
    public ApiResponse<AuthorityRequestModels.AuthorityRequest> reject(
            @PathVariable long requestId,
            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(service.reject(
                requestId, request.version(), userContext.require().username(), request.reason()));
    }

    public record SubmitRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String authorityId,
            @NotNull AuthorizationCatalog.AssignmentType assignmentType,
            @NotNull LocalDate validFrom,
            @NotNull LocalDate validTo,
            @Pattern(regexp = "[A-Za-z0-9_]{1,50}") String delegatedBy,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record DecisionRequest(
            @NotNull @Min(1) Long version,
            @Size(max = 500) String reason) {
    }

    public record RequestOptions(
            java.util.List<AuthorizationCatalog.AuthorityDefinition> authorities,
            java.util.List<AuthorizationCatalog.AssignmentType> assignmentTypes) {
    }
}
