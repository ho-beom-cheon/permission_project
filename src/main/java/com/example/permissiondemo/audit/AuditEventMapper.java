package com.example.permissiondemo.audit;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Oracle 감사 이벤트 SQL을 XML로 분리하는 Mapper다. */
@Mapper
public interface AuditEventMapper {

    long nextId();

    int insert(
            @Param("event") AuditRow event,
            @Param("detailsJson") String detailsJson);

    long countAll();

    List<AuditRow> selectPage(
            @Param("offset") long offset,
            @Param("size") int size);

    long countFiltered(@Param("query") AuditEventService.AuditQuery query);

    List<AuditRow> selectFilteredPage(
            @Param("query") AuditEventService.AuditQuery query,
            @Param("offset") long offset,
            @Param("size") int size);

    record AuditRow(
            long id,
            String eventType,
            String actor,
            String organizationId,
            String targetType,
            String targetId,
            String result,
            String clientIp,
            String traceId,
            LocalDateTime occurredAt,
            String detailsJson) {
    }
}
