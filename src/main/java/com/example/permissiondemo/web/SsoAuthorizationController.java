package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/sso-authorizations")
@StateBoundary
public class SsoAuthorizationController {
    private final SsoAuthorityScopeService service;
    public SsoAuthorizationController(SsoAuthorityScopeService service) { this.service = service; }
    @GetMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<SsoAuthorityScopeService.Mode>> list() { return ApiResponse.ok(service.list()); }
    @PostMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<SsoAuthorityScopeService.Mode> save(@RequestBody SsoAuthorityScopeService.WriteMode value) { return ApiResponse.ok(service.save(value)); }
}
