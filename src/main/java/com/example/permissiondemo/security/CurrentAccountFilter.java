package com.example.permissiondemo.security;

import java.io.IOException;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.web.ApiErrorWriter;
import com.example.permissiondemo.web.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 사용자 사용 중지는 기존 로그인 세션에도 다음 요청부터 반영한다. */
public class CurrentAccountFilter extends OncePerRequestFilter {
    private final AuthorizationCatalog catalog;
    private final ApiErrorWriter errors;
    public CurrentAccountFilter(AuthorizationCatalog catalog, ApiErrorWriter errors) { this.catalog = catalog; this.errors = errors; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && !catalog.findUser(auth.getName()).map(AuthorizationCatalog.UserProfile::active).orElse(false)) {
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) errors.write(request, response, ErrorCode.UNAUTHENTICATED);
            else response.sendRedirect(request.getContextPath() + "/login");
            return;
        }
        chain.doFilter(request, response);
    }
}
