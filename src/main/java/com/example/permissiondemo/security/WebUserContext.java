package com.example.permissiondemo.security;

import java.util.Optional;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import com.example.permissiondemo.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** HTTP 요청과 SecurityContext를 사용해 CurrentUserContext 계약을 구현한다. */
@Component
public class WebUserContext implements CurrentUserContext {

    private final AuthorizationCatalog catalog;
    private final HttpServletRequest request;

    public WebUserContext(AuthorizationCatalog catalog, HttpServletRequest request) {
        this.catalog = catalog;
        this.request = request;
    }

    /**
     * 인증 객체의 사용자명을 카탈로그 프로필과 연결해 현재 조직을 결정한다.
     * 클라이언트가 보낸 조직·사용자 값은 신뢰하지 않는다.
     */
    @Override
    public Optional<CurrentUser> find() {
        Authentication authentication = authentication();
        if (!isAuthenticated(authentication)) {
            return Optional.empty();
        }
        return catalog.findUser(authentication.getName())
                .filter(AuthorizationCatalog.UserProfile::active)
                .map(profile -> new CurrentUser(
                        profile.username(),
                        profile.organizationId(),
                        request.getRemoteAddr(),
                        traceId()));
    }

    /** 보호 서비스에서 사용할 필수 사용자 컨텍스트를 반환한다. */
    @Override
    public CurrentUser require() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    /** 현재 실행 스레드의 Spring Security 인증 객체를 조회한다. */
    @Override
    public Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** null, 미인증 토큰과 익명 토큰을 실제 로그인 사용자에서 제외한다. */
    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** 가장 먼저 실행된 TraceIdFilter가 요청 속성에 저장한 추적 ID를 읽는다. */
    private String traceId() {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ATTR);
        return value == null ? null : value.toString();
    }
}
