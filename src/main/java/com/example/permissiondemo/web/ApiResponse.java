package com.example.permissiondemo.web;

import java.util.List;

/**
 * 모든 JSON API가 공유하는 성공·실패 응답 봉투다.
 * 성공 시 data만, 실패 시 error만 채워 클라이언트 처리 규칙을 일관되게 유지한다.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    /** 성공 데이터가 포함된 응답을 만든다. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /** 경로·추적·필드 정보가 필요 없는 단순 실패 응답을 만든다. */
    public static <T> ApiResponse<T> fail(String code, String message) {
        return fail(code, message, null, null, List.of());
    }

    /** 운영 추적 정보와 필드 검증 오류까지 포함한 전체 실패 응답을 만든다. */
    public static <T> ApiResponse<T> fail(
            String code,
            String message,
            String path,
            String traceId,
            List<FieldError> fieldErrors) {
        return new ApiResponse<>(false, null,
                new ApiError(code, message, path, traceId, List.copyOf(fieldErrors)));
    }

    /** 실패 코드, 사용자 메시지, 요청 경로와 추적 정보를 담는 오류 본문이다. */
    public record ApiError(
            String code,
            String message,
            String path,
            String traceId,
            List<FieldError> fieldErrors) {
    }

    /** Bean Validation 실패 시 문제가 있는 요청 필드와 메시지를 나타낸다. */
    public record FieldError(String field, String message) {
    }
}
