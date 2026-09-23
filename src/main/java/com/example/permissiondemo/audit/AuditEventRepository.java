package com.example.permissiondemo.audit;

import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;

/** 감사 이벤트의 인메모리·Oracle 공통 저장 계약이다. */
public interface AuditEventRepository {

    long nextId();

    void append(AuditEventService.AuditEvent event);

    PageResult<AuditEventService.AuditEvent> findPage(PageQuery query);

    PageResult<AuditEventService.AuditEvent> findPage(AuditEventService.AuditQuery query);
}
