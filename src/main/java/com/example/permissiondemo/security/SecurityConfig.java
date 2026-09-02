package com.example.permissiondemo.security;

import java.util.Map;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.web.ApiErrorWriter;
import com.example.permissiondemo.web.ErrorCode;
import com.example.permissiondemo.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 데모 프로필의 세션 로그인, URL 보호, CSRF와 인증·인가 실패 응답을 구성한다.
 * Spring 로그인 역할은 인증 완료 표시만 담당하고 실제 업무 권한은 별도 카탈로그에서 계산한다.
 */
@Configuration
@EnableMethodSecurity
@Profile("demo")
public class SecurityConfig {

    @Bean org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }

    @Bean org.springframework.security.web.session.HttpSessionEventPublisher sessionEvents() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }

    /**
     * 웹 보안 필터 체인을 구성한다.
     * API 실패는 HTML 리다이렉트가 아닌 표준 JSON으로 반환하고 모든 보안 이벤트를 감사 이력에 남긴다.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiErrorWriter errorWriter,
            AuditEventService auditEventService,
            AuthorizationCatalog catalog,
            org.springframework.security.core.session.SessionRegistry sessionRegistry) throws Exception {
        // 로그인 화면과 일반 페이지는 HTML 흐름을 사용하고 /api 하위 요청만 JSON 오류 처리 대상으로 삼는다.
        RequestMatcher apiRequest = request -> {
            String contextPath = request.getContextPath();
            String requestPath = request.getRequestURI().substring(contextPath.length());
            return requestPath.equals("/api") || requestPath.startsWith("/api/");
        };
        SavedRequestAwareAuthenticationSuccessHandler successHandler =
                new SavedRequestAwareAuthenticationSuccessHandler();
        // 로그인 직후에는 권한 결과가 실제 메뉴와 기능에 반영되는 사용자 업무 포털로 이동한다.
        successHandler.setDefaultTargetUrl("/portal.html");
        successHandler.setAlwaysUseDefaultTargetUrl(true);
        SimpleUrlAuthenticationFailureHandler failureHandler =
                new SimpleUrlAuthenticationFailureHandler("/login?error");
        LoginUrlAuthenticationEntryPoint loginEntryPoint =
                new LoginUrlAuthenticationEntryPoint("/login");

        http
                .authorizeHttpRequests(authorize -> authorize
                        // 로그인 화면 구성 파일과 로그인 폼용 CSRF 조회만 익명 접근을 허용한다.
                        .requestMatchers(
                                "/login", "/login.html", "/login.css", "/login.js",
                                "/login-polish.css",
                                "/api/csrf", "/error", "/favicon.ico")
                        .permitAll()
                        // API는 업무 권한과 별개로 우선 로그인 세션이 있어야 한다.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.maximumSessions(-1).sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event -> {
                            var request = event.getRequest(); var response = event.getResponse();
                            if (apiRequest.matches(request)) errorWriter.write(request,response,ErrorCode.UNAUTHENTICATED);
                            else response.sendRedirect(request.getContextPath()+"/login");
                        }))
                .formLogin(form -> form
                        // GET /login은 자체 화면을 사용하고 POST /login은 Security가 인증 처리한다.
                        .loginPage("/login")
                        .successHandler((request, response, authentication) -> {
                            // 로그인 성공을 먼저 기록한 뒤 항상 사용자 업무 포털로 이동한다.
                            recordSecurity(
                                    request,
                                    authentication.getName(),
                                    "LOGIN_SUCCESS",
                                    "SUCCESS",
                                    auditEventService,
                                    catalog);
                            successHandler.onAuthenticationSuccess(
                                    request, response, authentication);
                        })
                        .failureHandler((request, response, exception) -> {
                            // 실패 시 입력 사용자명은 로그 주입을 막기 위해 정리한 뒤 기록한다.
                            recordSecurity(
                                    request,
                                    safeActor(request.getParameter("username")),
                                    "LOGIN_FAILURE",
                                    "FAILED",
                                    auditEventService,
                                    catalog);
                            failureHandler.onAuthenticationFailure(request, response, exception);
                        }))
                .logout(logout -> logout
                        .addLogoutHandler((request, response, authentication) -> {
                            // 세션이 정리되기 전에 행위자를 확보해 로그아웃 이력을 남긴다.
                            String actor = authentication == null
                                    ? "UNKNOWN" : authentication.getName();
                            recordSecurity(
                                    request,
                                    actor,
                                    "LOGOUT",
                                    "SUCCESS",
                                    auditEventService,
                                    catalog);
                        })
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(exceptions -> exceptions
                        // 단일 진입점에서 API와 일반 HTML을 구분해 서로 다른 실패 형식을 확실히 적용한다.
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (!apiRequest.matches(request)) {
                                loginEntryPoint.commence(request, response, exception);
                                return;
                            }
                            // 인증이 없는 API 요청은 401 JSON과 감사 이벤트로 처리한다.
                            recordSecurity(
                                    request,
                                    "ANONYMOUS",
                                    "AUTH_REQUIRED",
                                    "DENIED",
                                    auditEventService,
                                    catalog);
                            errorWriter.write(
                                    request, response, ErrorCode.UNAUTHENTICATED);
                        })
                        .defaultAccessDeniedHandlerFor(
                                (request, response, exception) -> {
                                    // CSRF 또는 URL 인가 거부도 같은 403 응답 형식과 감사 기준을 사용한다.
                                    Authentication authentication =
                                            org.springframework.security.core.context
                                                    .SecurityContextHolder.getContext()
                                                    .getAuthentication();
                                    String actor = authentication == null
                                            ? "ANONYMOUS" : authentication.getName();
                                    recordSecurity(
                                            request,
                                            actor,
                                            "ACCESS_DENIED",
                                            "DENIED",
                                            auditEventService,
                                            catalog);
                                    errorWriter.write(
                                            request, response, ErrorCode.ACCESS_DENIED);
                                },
                                apiRequest));

        http.addFilterAfter(new CurrentAccountFilter(catalog, errorWriter),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);

        // CSRF, 세션 고정 공격 방어, 보안 헤더는 Spring Security 기본값을 사용한다.
        return http.build();
    }

    /** 데모 비밀번호도 평문 비교하지 않도록 Spring의 위임형 PasswordEncoder를 사용한다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 보안 필터 단계의 사건에 사용자 조직과 요청 추적 정보를 결합해 감사 이벤트를 기록한다. */
    private void recordSecurity(
            HttpServletRequest request,
            String actor,
            String eventType,
            String result,
            AuditEventService auditEventService,
            AuthorizationCatalog catalog) {
        String organizationId = catalog.findUser(actor)
                .map(AuthorizationCatalog.UserProfile::organizationId)
                .orElse("UNKNOWN");
        Object traceValue = request.getAttribute(TraceIdFilter.TRACE_ATTR);
        auditEventService.recordAs(
                actor,
                organizationId,
                request.getRemoteAddr(),
                traceValue == null ? null : traceValue.toString(),
                eventType,
                "HTTP_SESSION",
                request.getRequestURI(),
                result,
                Map.of("method", request.getMethod()));
    }

    /** 실패 로그인 입력에서 줄바꿈을 제거하고 최대 길이를 제한해 감사 로그 주입을 방지한다. */
    private String safeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return "UNKNOWN";
        }
        String sanitized = actor.replace("\r", "").replace("\n", "");
        return sanitized.substring(0, Math.min(sanitized.length(), 60));
    }
}
