package com.example.permissiondemo.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/** ErrorCode와 현재 HTTP 요청 정보를 결합해 표준 실패 응답을 생성한다. */
@Component
public class ApiErrorFactory {

    private final MessageResolver messageResolver;

    public ApiErrorFactory(MessageResolver messageResolver) {
        this.messageResolver = messageResolver;
    }

    /** 필드 오류가 없는 기본 실패 응답을 만든다. */
    public ApiResponse<Void> create(ErrorCode errorCode, HttpServletRequest request) {
        return create(errorCode, request, List.of());
    }

    /** 메시지 번들과 선택적 필드 오류를 적용한 실패 응답을 만든다. */
    public ApiResponse<Void> create(
            ErrorCode errorCode,
            HttpServletRequest request,
            List<ApiResponse.FieldError> fieldErrors,
            Object... messageArgs) {
        return createMessage(
                errorCode,
                messageResolver.resolve(errorCode, messageArgs),
                request,
                fieldErrors);
    }

    /**
     * 검증기나 하위 시스템에서 이미 안전한 사용자 메시지를 정한 경우 해당 메시지를 그대로 사용한다.
     * 요청 경로와 TraceIdFilter가 설정한 traceId는 여기서 공통으로 결합한다.
     */
    public ApiResponse<Void> createMessage(
            ErrorCode errorCode,
            String message,
            HttpServletRequest request,
            List<ApiResponse.FieldError> fieldErrors) {
        Object traceValue = request.getAttribute(TraceIdFilter.TRACE_ATTR);
        String traceId = traceValue == null ? null : traceValue.toString();
        return ApiResponse.fail(
                errorCode.name(),
                message,
                request.getRequestURI(),
                traceId,
                fieldErrors);
    }
}
