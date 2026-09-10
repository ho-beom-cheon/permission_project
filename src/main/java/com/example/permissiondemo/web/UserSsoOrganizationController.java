package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@StateBoundary
public class UserSsoOrganizationController {
    private final UserSsoOrganizationService service;
    public UserSsoOrganizationController(UserSsoOrganizationService service) { this.service = service; }

    @GetMapping("/api/admin/user-sso-organizations")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<UserSsoOrganizationService.Affiliation>> list() { return ApiResponse.ok(service.list()); }

    @PostMapping("/api/admin/user-sso-organizations")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<UserSsoOrganizationService.Affiliation> save(@RequestBody UserSsoOrganizationService.WriteAffiliation value) {
        return ApiResponse.ok(service.save(value));
    }

    @GetMapping("/api/colleagues")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'DIRECTORY_LOOKUP')")
    public ApiResponse<UserSsoOrganizationService.LookupResult> colleagues(@RequestParam(required=false) String name,
            @RequestParam(required=false) String officeCode, @RequestParam(required=false) String ssoDepartmentName,
            @RequestParam(required=false) String authId,
            @RequestParam(required=false) com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus procStatus,
            @RequestParam(required=false) String systemPrefix) {
        return ApiResponse.ok(service.colleagues(name, officeCode, ssoDepartmentName, authId, procStatus, systemPrefix));
    }
}
