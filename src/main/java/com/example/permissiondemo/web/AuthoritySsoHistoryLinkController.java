package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.authorization.AuthoritySsoHistoryLinkService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/authority-sso-history-links")
@StateBoundary
public class AuthoritySsoHistoryLinkController {
    private final AuthoritySsoHistoryLinkService service;
    public AuthoritySsoHistoryLinkController(AuthoritySsoHistoryLinkService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<AuthoritySsoHistoryLinkService.Link>> list() { return ApiResponse.ok(service.list()); }

    @PostMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthoritySsoHistoryLinkService.Link> save(@RequestBody AuthoritySsoHistoryLinkService.WriteLink value) {
        return ApiResponse.ok(service.save(value));
    }
}
