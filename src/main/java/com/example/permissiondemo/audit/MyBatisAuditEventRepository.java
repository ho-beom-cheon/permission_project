package com.example.permissiondemo.audit;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** oracle 프로필에서 감사 이벤트를 append-only 테이블에 영속화한다. */
@Repository
@Profile("oracle")
public class MyBatisAuditEventRepository implements AuditEventRepository {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final TypeReference<Map<String, Object>> DETAILS_TYPE = new TypeReference<>() { };

    private final AuditEventMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisAuditEventRepository(AuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public long nextId() {
        return mapper.nextId();
    }

    @Override
    public void append(AuditEventService.AuditEvent event) {
        try {
            mapper.insert(new AuditEventMapper.AuditRow(
                            event.id(), event.eventType(), event.actor(), event.organizationId(),
                            event.targetType(), event.targetId(), event.result(), event.clientIp(),
                            event.traceId(), event.occurredAt().atZone(SEOUL).toLocalDateTime(), null),
                    objectMapper.writeValueAsString(event.details()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("감사 상세 JSON 직렬화에 실패했습니다.", exception);
        }
    }

    @Override
    public PageResult<AuditEventService.AuditEvent> findPage(PageQuery query) {
        long total = mapper.countAll();
        long offset = (long) query.page() * query.size();
        List<AuditEventService.AuditEvent> content = mapper.selectPage(offset, query.size()).stream()
                .map(this::toEvent)
                .toList();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / query.size());
        return new PageResult<>(content, query.page(), query.size(), total, totalPages);
    }

    @Override
    public PageResult<AuditEventService.AuditEvent> findPage(AuditEventService.AuditQuery query) {
        long total = mapper.countFiltered(query);
        long offset = (long) query.page().page() * query.page().size();
        List<AuditEventService.AuditEvent> content = mapper
                .selectFilteredPage(query, offset, query.page().size()).stream()
                .map(this::toEvent)
                .toList();
        int totalPages = total == 0 ? 0
                : (int) Math.ceil((double) total / query.page().size());
        return new PageResult<>(content, query.page().page(), query.page().size(), total, totalPages);
    }

    private AuditEventService.AuditEvent toEvent(AuditEventMapper.AuditRow row) {
        try {
            Map<String, Object> details = row.detailsJson() == null || row.detailsJson().isBlank()
                    ? Map.of() : objectMapper.readValue(row.detailsJson(), DETAILS_TYPE);
            return new AuditEventService.AuditEvent(
                    row.id(), row.eventType(), row.actor(), row.organizationId(),
                    row.targetType(), row.targetId(), row.result(), row.clientIp(), row.traceId(),
                    row.occurredAt().atZone(SEOUL).toInstant(), details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("감사 상세 JSON 역직렬화에 실패했습니다.", exception);
        }
    }
}
