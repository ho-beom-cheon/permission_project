package com.example.permissiondemo.common;

/**
 * API 목록 조회에서 공통으로 사용하는 0 기반 페이지 요청이다.
 * 비정상적으로 큰 응답을 막기 위해 페이지 크기를 최대 100건으로 제한한다.
 */
public record PageQuery(int page, int size) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    /** null 값을 기본값으로 치환하고 허용 범위를 검증한 페이지 요청을 만든다. */
    public static PageQuery of(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0) {
            throw new IllegalArgumentException("page는 0 이상이어야 합니다.");
        }
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new IllegalArgumentException("size는 1~100 범위여야 합니다.");
        }
        return new PageQuery(resolvedPage, resolvedSize);
    }
}
