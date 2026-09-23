package com.example.permissiondemo.web;

import com.example.permissiondemo.common.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 응답과 관리자 응답을 분리하되 기존 공통 부트스트랩 계약은 유지한다. */
@RestController
public class WorkspaceController {
    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/api/portal/bootstrap")
    public ApiResponse<PortalBootstrap> portal(@RequestAttribute("_csrf") CsrfToken token) {
        return ApiResponse.ok(new PortalBootstrap(service.portal(), csrf(token)));
    }

    @GetMapping("/api/admin/bootstrap")
    @PreAuthorize("@workspaceService.canAccessAdmin(authentication)")
    public ApiResponse<BootstrapController.BootstrapView> admin(@RequestAttribute("_csrf") CsrfToken token) {
        return ApiResponse.ok(new BootstrapController.BootstrapView(service.admin(), csrf(token)));
    }

    private PermissionApiController.CsrfView csrf(CsrfToken token) {
        return new PermissionApiController.CsrfView(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    public record PortalBootstrap(WorkspaceService.PortalView workspace, PermissionApiController.CsrfView csrf) {}
}
