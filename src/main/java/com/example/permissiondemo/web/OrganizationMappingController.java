package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/organization-mappings")
@StateBoundary
public class OrganizationMappingController {
    private final OrganizationMappingService service;
    public OrganizationMappingController(OrganizationMappingService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<OrganizationMappingService.Mapping>> list() { return ApiResponse.ok(service.list()); }

    @PostMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<OrganizationMappingService.Mapping> save(@RequestBody OrganizationMappingService.WriteMapping value) {
        return ApiResponse.ok(service.save(value));
    }
}
