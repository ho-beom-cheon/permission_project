package com.example.permissiondemo.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 HTTP 요청에 고유 traceId를 발급해 응답 헤더, 오류 응답과 서버 로그를 연결한다.
 * 보안 필터보다 먼저 실행되어 인증 단계에서 실패한 요청도 같은 추적 ID를 갖는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ATTR = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    /**
     * 요청 속성과 {@code X-Trace-Id} 응답 헤더, SLF4J MDC에 같은 값을 설정한다.
     * 스레드 풀 재사용 시 다른 요청으로 값이 새지 않도록 finally에서 MDC를 반드시 제거한다.
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(TRACE_ATTR, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        MDC.put(TRACE_ATTR, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ATTR);
        }
    }
}
