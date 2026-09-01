package com.example.permissiondemo.web;

/**
 * 서비스 계층이 HTTP 구현을 직접 알지 않고 표준 오류 코드와 메시지 인자만 전달하는 업무 예외다.
 * 최종 HTTP 상태와 응답 본문 생성은 ApiExceptionHandler가 담당한다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object[] messageArgs;

    public ApiException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.messageArgs = messageArgs.clone();
    }

    /** 예외에 연결된 표준 오류 코드를 반환한다. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 외부 변경을 막기 위해 메시지 치환 인자의 복사본을 반환한다. */
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
