package com.example.permissiondemo.web;

import com.example.permissiondemo.authorization.AuthorityHistoryService;
import com.example.permissiondemo.authorization.AuthorityHistoryQueryService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 본인 처리 이력은 세션 사용자로 제한하고 관리자 조회는 별도 권한으로 보호한다. */
@RestController
public class AuthorityHistoryController {
    private final AuthorityHistoryService history;
    private final CurrentUserContext current;
    private final AuthorityHistoryQueryService query;

    public AuthorityHistoryController(AuthorityHistoryService history, CurrentUserContext current, AuthorityHistoryQueryService query) {
        this.history = history;
        this.current = current;
        this.query = query;
    }

    @GetMapping("/api/me/authority-history")
    public ApiResponse<PageResult<AuthorityHistoryService.HistoryEvent>> mine(
            @RequestParam(required=false) AuthorityHistoryService.HistoryStatus status,
            @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        return ApiResponse.ok(history.list(current.require().username(), status, PageQuery.of(page, size)));
    }

    @GetMapping("/api/admin/authority-history")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AuthorityHistoryService.HistoryEvent>> all(
            @RequestParam(required=false) String username,
            @RequestParam(required=false) AuthorityHistoryService.HistoryStatus status,
            @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        String target = username == null || username.isBlank() ? null : username.trim();
        return ApiResponse.ok(history.list(target, status, PageQuery.of(page, size)));
    }

    @GetMapping("/api/me/authority-history/full")
    public ApiResponse<PageResult<AuthorityHistoryQueryService.HistoryRow>> mineFull(
            @RequestParam(required=false) AuthorityHistoryService.HistoryStatus status,
            @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        return ApiResponse.ok(query.list(current.require().username(), status, PageQuery.of(page, size)));
    }

    @GetMapping("/api/admin/authority-history/full")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AuthorityHistoryQueryService.HistoryRow>> allFull(
            @RequestParam(required=false) String username,
            @RequestParam(required=false) AuthorityHistoryService.HistoryStatus status,
            @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        return ApiResponse.ok(query.list(username == null || username.isBlank() ? null : username.trim(),
                status, PageQuery.of(page, size)));
    }

    @PatchMapping("/api/admin/authority-history/reason")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorityHistoryService.HistoryEvent> correctReason(
            @jakarta.validation.Valid @RequestBody ReasonCorrectionRequest request) {
        return ApiResponse.ok(history.correctLatestReason(request.username(), request.authorityId(),
                request.ssoOfficeCode(), request.ssoDepartmentCode(), request.reason()));
    }

    public record ReasonCorrectionRequest(
            @jakarta.validation.constraints.NotBlank String username,
            @jakarta.validation.constraints.NotBlank String authorityId,
            @jakarta.validation.constraints.NotBlank String ssoOfficeCode,
            @jakarta.validation.constraints.NotBlank String ssoDepartmentCode,
            @jakarta.validation.constraints.NotBlank String reason) { }
}
