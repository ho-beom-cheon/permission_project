package com.example.permissiondemo.web;
import java.util.List;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityRevocationService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/admin/authority-revocations")
public class AuthorityRevocationController {
    private final AuthorizationCatalog catalog;private final AuthorityRevocationService service;
    public AuthorityRevocationController(AuthorizationCatalog catalog,AuthorityRevocationService service){this.catalog=catalog;this.service=service;}
    @GetMapping("/options")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<Options> options(){return ApiResponse.ok(new Options(catalog.users(),catalog.authorities().stream().map(AuthorizationCatalog.AuthorityDefinition::systemId).distinct().sorted().toList(),catalog.authorityVersion()));}
    @PostMapping("/preview")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<AuthorityRevocationService.Plan> preview(@RequestBody AuthorityRevocationService.Request request){return ApiResponse.ok(service.preview(request));}
    @PostMapping
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorityRevocationService.Plan> revoke(@RequestBody AuthorityRevocationService.Request request){return ApiResponse.ok(service.revoke(request));}
    public record Options(List<AuthorizationCatalog.UserProfile> users,List<String> systems,long authorityVersion) { }
}
