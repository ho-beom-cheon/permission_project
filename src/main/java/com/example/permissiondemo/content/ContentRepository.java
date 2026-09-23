package com.example.permissiondemo.content;

import java.util.List;
import java.util.Optional;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;

public interface ContentRepository {
    long nextId();
    Optional<ContentPost> findById(long id);
    PageResult<ContentPost> search(String field, String query, PageQuery page);
    void insert(ContentPost post);
    /** 변경 버전의 직전 버전과 일치할 때만 전체 항목을 원자적으로 교체한다. */
    void updateAll(List<ContentPost> changes);
}
