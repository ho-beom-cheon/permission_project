package com.example.permissiondemo.web;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트가 분기 처리할 안정적인 오류 코드와 HTTP 상태, 다국어 메시지 키를 정의한다.
 * 예외 클래스명이나 내부 구현 메시지를 API 계약으로 노출하지 않기 위한 기준 목록이다.
 */
public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "error.unauthenticated", "로그인이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "error.access-denied", "요청한 기능을 실행할 권한이 없습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "error.invalid-request", "요청값을 확인해 주세요."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.not-found", "요청한 대상을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "error.conflict", "현재 상태에서는 요청을 처리할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal", "요청 처리 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
        this.status = status;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    /** 오류에 대응하는 HTTP 응답 상태다. */
    public HttpStatus status() {
        return status;
    }

    /** MessageSource에서 사용자 메시지를 찾을 때 사용하는 키다. */
    public String messageKey() {
        return messageKey;
    }

    /** 메시지 번들에 키가 없을 때 사용하는 안전한 기본 메시지다. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
