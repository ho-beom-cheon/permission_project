package com.example.permissiondemo.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** demo 프로필에서 최근 감사 이벤트 500건을 보관한다. */
@Repository
@Profile("demo")
public class InMemoryAuditEventRepository implements AuditEventRepository {

    private static final int MAX_EVENTS = 500;
    private final ConcurrentLinkedDeque<AuditEventService.AuditEvent> events =
            new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public void append(AuditEventService.AuditEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    @Override
    public PageResult<AuditEventService.AuditEvent> findPage(PageQuery query) {
        return PageResult.of(new ArrayList<>(events), query);
    }

    @Override
    public PageResult<AuditEventService.AuditEvent> findPage(AuditEventService.AuditQuery query) {
        String keyword = lower(query.keyword());
        Comparator<AuditEventService.AuditEvent> comparator = switch (query.sort()) {
            case OCCURRED_AT -> Comparator.comparing(AuditEventService.AuditEvent::occurredAt)
                    .thenComparingLong(AuditEventService.AuditEvent::id);
            case ACTOR -> Comparator.comparing(AuditEventService.AuditEvent::actor)
                    .thenComparingLong(AuditEventService.AuditEvent::id);
            case EVENT_TYPE -> Comparator.comparing(AuditEventService.AuditEvent::eventType)
                    .thenComparingLong(AuditEventService.AuditEvent::id);
            case TARGET -> Comparator.comparing(AuditEventService.AuditEvent::targetType)
                    .thenComparing(AuditEventService.AuditEvent::targetId);
            case RESULT -> Comparator.comparing(AuditEventService.AuditEvent::result)
                    .thenComparingLong(AuditEventService.AuditEvent::id);
        };
        if (query.direction() == com.example.permissiondemo.common.SortDirection.DESC) {
            comparator = comparator.reversed();
        }
        return PageResult.of(events.stream()
                .filter(event -> keyword == null || searchable(event).contains(keyword))
                .filter(event -> matches(event.eventType(), query.eventType()))
                .filter(event -> matches(event.actor(), query.actor()))
                .filter(event -> matches(event.targetType(), query.targetType()))
                .filter(event -> matches(event.result(), query.result()))
                .filter(event -> query.from() == null || !event.occurredAt().isBefore(query.from()))
                .filter(event -> query.toExclusive() == null || event.occurredAt().isBefore(query.toExclusive()))
                .sorted(comparator)
                .toList(), query.page());
    }

    private boolean matches(String value, String filter) {
        return filter == null || value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String searchable(AuditEventService.AuditEvent event) {
        return String.join(" ", event.eventType(), event.actor(), event.organizationId(),
                event.targetType(), event.targetId(), event.result(),
                event.traceId() == null ? "" : event.traceId()).toLowerCase(Locale.ROOT);
    }
}
