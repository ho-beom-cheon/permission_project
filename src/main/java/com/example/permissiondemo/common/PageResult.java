package com.example.permissiondemo.common;

import java.util.List;

/** 목록 내용과 현재 페이지, 전체 건수·페이지 수를 함께 반환하는 불변 페이징 응답이다. */
public record PageResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /**
     * 이미 정렬된 전체 목록에서 요청 페이지 구간을 안전하게 잘라 결과를 만든다.
     * offset 계산은 long으로 수행해 매우 큰 page 값의 정수 오버플로를 방지한다.
     */
    public static <T> PageResult<T> of(List<T> source, PageQuery query) {
        long offset = (long) query.page() * query.size();
        int start = (int) Math.min(offset, source.size());
        int end = Math.min(start + query.size(), source.size());
        int totalPages = source.isEmpty()
                ? 0 : (int) Math.ceil((double) source.size() / query.size());
        return new PageResult<>(
                List.copyOf(source.subList(start, end)),
                query.page(),
                query.size(),
                source.size(),
                totalPages);
    }
}
