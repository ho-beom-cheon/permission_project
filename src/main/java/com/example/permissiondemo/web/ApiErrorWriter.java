package com.example.permissiondemo.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * MVC 예외 처리기에 도달하기 전 Spring Security 필터 단계의 오류를 JSON으로 직접 기록한다.
 * 인증·CSRF 실패도 일반 API 예외와 동일한 ApiResponse 형식을 사용하게 한다.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;
    private final ApiErrorFactory errorFactory;

    public ApiErrorWriter(ObjectMapper objectMapper, ApiErrorFactory errorFactory) {
        this.objectMapper = objectMapper;
        this.errorFactory = errorFactory;
    }

    /** HTTP 상태·UTF-8 JSON 헤더를 설정하고 표준 오류 본문을 응답 스트림에 직렬화한다. */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), errorFactory.create(errorCode, request));
    }
}
