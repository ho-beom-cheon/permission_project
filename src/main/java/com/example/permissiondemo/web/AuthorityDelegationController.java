package com.example.permissiondemo.web;

import java.util.List;

import com.example.permissiondemo.authorization.AuthorityDelegationService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.security.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 사용자가 원본과 같은 별도 권한위임 한 건을 등록·취소한다. */
@RestController
@com.example.permissiondemo.storage.StateBoundary
public class AuthorityDelegationController {
    private final AuthorityDelegationService service;
    private final AuthorizationCatalog catalog;
    private final CurrentUserContext current;

    public AuthorityDelegationController(AuthorityDelegationService service, AuthorizationCatalog catalog,
            CurrentUserContext current) {
        this.service = service;
        this.catalog = catalog;
        this.current = current;
    }

    @GetMapping("/api/me/authority-delegation")
    public ApiResponse<DelegationView> view() {
        String username = current.require().username();
        return ApiResponse.ok(new DelegationView(service.active(username).orElse(null),
                service.candidateAuthorities(username), catalog.users().stream()
                        .filter(AuthorizationCatalog.UserProfile::active)
                        .filter(user -> !user.username().equals(username)).toList()));
    }

    @PostMapping("/api/me/authority-delegation")
    public ApiResponse<AuthorityDelegationService.Delegation> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(current.require().username(), request.recipientUsername(),
                request.authorityId(), request.reason()));
    }

    @DeleteMapping("/api/me/authority-delegation")
    public ApiResponse<AuthorityDelegationService.Delegation> cancel() {
        return ApiResponse.ok(service.cancel(current.require().username()));
    }

    public record DelegationView(AuthorityDelegationService.Delegation active,
            List<AuthorizationCatalog.AuthorityDefinition> authorities,
            List<AuthorizationCatalog.UserProfile> recipients) { }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,50}") String recipientUsername,
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,50}") String authorityId,
            @NotBlank @Size(max = 1000) String reason) { }
}
