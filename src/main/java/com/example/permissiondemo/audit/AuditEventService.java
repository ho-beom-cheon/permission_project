package com.example.permissiondemo.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 로그인, 접근 거부와 관리 데이터 변경을 최근 순으로 조회한다.
 * 변경 이벤트는 업무 상태와 같은 로컬 저장 트랜잭션으로 보존한다.
 */
@Service
@com.example.permissiondemo.storage.StateBoundary
public class AuditEventService implements com.example.permissiondemo.storage.StateParticipant {

    private final ConcurrentLinkedDeque<AuditEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final CurrentUserContext userContext;
    private final Clock clock;

    /** 단위 테스트에서 웹 요청 컨텍스트 없이 사용할 수 있는 시스템 행위자용 생성자다. */
    public AuditEventService() {
        this(null, Clock.systemUTC());
    }

    /** 실제 요청에서는 현재 사용자와 공통 Clock을 주입받아 감사 주체와 발생 시각을 기록한다. */
    @Autowired
    public AuditEventService(CurrentUserContext userContext, Clock clock) {
        this.userContext = userContext;
        this.clock = clock;
    }

    /**
     * 현재 요청의 사용자·조직·IP·traceId를 자동으로 결합해 감사 이벤트를 기록한다.
     * 인증 사용자가 없는 내부 호출은 SYSTEM 행위자로 남긴다.
     */
    public void record(
            String eventType,
            String targetType,
            String targetId,
            String result,
            Map<String, ?> details) {
        Optional<CurrentUserContext.CurrentUser> current = userContext == null
                ? Optional.empty() : userContext.find();
        recordAs(
                current.map(CurrentUserContext.CurrentUser::username).orElse("SYSTEM"),
                current.map(CurrentUserContext.CurrentUser::organizationId).orElse("SYSTEM"),
                current.map(CurrentUserContext.CurrentUser::clientIp).orElse(null),
                current.map(CurrentUserContext.CurrentUser::traceId).orElse(null),
                eventType,
                targetType,
                targetId,
                result,
                details);
    }

    /**
     * 로그인 성공·실패처럼 SecurityContext가 아직 구성되지 않았거나 이미 정리된 시점에
     * 호출자가 행위자 정보를 명시해 감사 이벤트를 기록한다.
     */
    public void recordAs(
            String actor,
            String organizationId,
            String clientIp,
            String traceId,
            String eventType,
            String targetType,
            String targetId,
            String result,
            Map<String, ?> details) {
        AuditEvent event = new AuditEvent(
                sequence.incrementAndGet(),
                eventType,
                actor,
                organizationId,
                targetType,
                targetId,
                result,
                clientIp,
                traceId,
                Instant.now(clock),
                Map.copyOf(details));
        // 최신 이벤트를 앞에 넣으며 기존 이력을 건수 기준으로 버리지 않는다.
        events.addFirst(event);
    }

    /** 최신 이벤트가 먼저 오도록 저장된 순서를 유지한 채 요청 페이지를 반환한다. */
    public PageResult<AuditEvent> findPage(PageQuery query) {
        return PageResult.of(new ArrayList<>(events), query);
    }

    /** 한 건의 감사 이벤트와 추적·변경 상세 정보다. details에는 민감한 원문을 넣지 않는다. */
    public record AuditEvent(
            long id,
            String eventType,
            String actor,
            String organizationId,
            String targetType,
            String targetId,
            String result,
            String clientIp,
            String traceId,
            Instant occurredAt,
            Map<String, ?> details) {
    }

    @Override public String stateKey() { return "audit"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(List.copyOf(events), sequence.get()); }
    @Override public void restoreState(Object raw) {
        StoredState state = (StoredState) raw;
        events.clear(); events.addAll(state.events()); sequence.set(state.sequence());
    }
    public record StoredState(List<AuditEvent> events, long sequence) { }
}
