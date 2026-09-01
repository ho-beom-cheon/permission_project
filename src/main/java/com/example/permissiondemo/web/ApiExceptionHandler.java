package com.example.permissiondemo.web;

import java.util.List;
import java.util.Map;

import com.example.permissiondemo.audit.AuditEventService;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

/**
 * MVC 계층에서 발생한 예외를 분류해 일관된 ApiResponse 실패 형식으로 변환한다.
 * 예상하지 못한 예외의 내부 내용은 숨기고 traceId와 전체 스택은 서버 로그에만 남긴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiErrorFactory errorFactory;
    private final AuditEventService auditEventService;

    public ApiExceptionHandler(
            ApiErrorFactory errorFactory,
            AuditEventService auditEventService) {
        this.errorFactory = errorFactory;
        this.auditEventService = auditEventService;
    }

    /** 서비스가 의도적으로 발생시킨 ApiException을 연결된 ErrorCode 상태로 반환한다. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(
            ApiException exception,
            HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();
        if (errorCode == ErrorCode.ACCESS_DENIED) {
            auditEventService.record(
                    "ACCESS_DENIED",
                    "HTTP_REQUEST",
                    request.getRequestURI(),
                    "DENIED",
                    Map.of("method", request.getMethod()));
        }
        return ResponseEntity.status(errorCode.status())
                .body(errorFactory.create(
                        errorCode, request, List.of(), exception.messageArgs()));
    }

    /** 코드 형식·기간·페이징 범위 등 서비스 입력 검증 실패를 400으로 반환한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(errorFactory.createMessage(
                ErrorCode.INVALID_REQUEST,
                exception.getMessage(),
                request,
                List.of()));
    }

    /** Bean Validation의 필드별 오류를 클라이언트가 입력란에 매핑할 수 있는 목록으로 변환한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiResponse.FieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors().stream()
                .map(error -> new ApiResponse.FieldError(
                        error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(errorFactory.create(
                ErrorCode.INVALID_REQUEST, request, fieldErrors));
    }

    /** 파싱할 수 없는 JSON이나 날짜 형식을 내부 Jackson 예외 없이 안전한 400 메시지로 변환한다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(errorFactory.createMessage(
                ErrorCode.INVALID_REQUEST,
                "JSON 요청 형식 또는 날짜 값을 확인해 주세요.",
                request,
                List.of()));
    }

    /** 하위 코드에서 발생한 HTTP 상태 예외를 표준 리소스 또는 입력 오류 응답으로 변환한다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        ErrorCode errorCode = exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()
                ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.INVALID_REQUEST;
        String message = exception.getReason() == null
                ? errorCode.defaultMessage() : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .body(errorFactory.createMessage(errorCode, message, request, List.of()));
    }

    /** 존재하지 않는 정적 자원 요청을 내부 오류가 아닌 404로 명확히 반환한다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.status())
                .body(errorFactory.create(ErrorCode.RESOURCE_NOT_FOUND, request));
    }

    /** 메서드 보안에서 거부된 요청을 감사 기록한 뒤 공통 403 응답으로 반환한다. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        auditEventService.record(
                "ACCESS_DENIED",
                "HTTP_REQUEST",
                request.getRequestURI(),
                "DENIED",
                Map.of("method", request.getMethod()));
        return ResponseEntity.status(ErrorCode.ACCESS_DENIED.status())
                .body(errorFactory.create(ErrorCode.ACCESS_DENIED, request));
    }

    /**
     * 별도 분류되지 않은 예외의 최종 안전망이다.
     * 사용자에게는 일반 메시지만 보내고 서버 로그에는 traceId와 원인 예외를 함께 기록한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ATTR);
        log.error("Unhandled API error: traceId={}", traceId, exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(errorFactory.create(ErrorCode.INTERNAL_ERROR, request));
    }
}
