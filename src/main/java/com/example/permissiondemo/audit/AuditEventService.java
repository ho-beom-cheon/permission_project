package com.example.permissiondemo.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.SortDirection;
import com.example.permissiondemo.security.CurrentUserContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 로그인, 접근 거부와 관리 데이터 변경을 프로필별 감사 저장소에 남기는 서비스다.
 * demo 프로필은 최근 이벤트를 메모리에, oracle 프로필은 append-only 테이블에 보관한다.
 */
@Service
public class AuditEventService {

    private final CurrentUserContext userContext;
    private final Clock clock;
    private final AuditEventRepository repository;

    /** 단위 테스트에서 웹 요청 컨텍스트 없이 사용할 수 있는 시스템 행위자용 생성자다. */
    public AuditEventService() {
        this(null, Clock.systemUTC(), new InMemoryAuditEventRepository());
    }

    /** 실제 요청에서는 현재 사용자와 공통 Clock을 주입받아 감사 주체와 발생 시각을 기록한다. */
    @Autowired
    public AuditEventService(
            CurrentUserContext userContext,
            Clock clock,
            AuditEventRepository repository) {
        this.userContext = userContext;
        this.clock = clock;
        this.repository = repository;
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
                repository.nextId(),
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
        repository.append(event);
    }

    /** 최신 이벤트가 먼저 오도록 저장된 순서를 유지한 채 요청 페이지를 반환한다. */
    public PageResult<AuditEvent> findPage(PageQuery query) {
        return repository.findPage(query);
    }

    /** 감사 화면의 검색·필터·정렬 조건을 저장소에 전달한다. */
    public PageResult<AuditEvent> findPage(
            String keyword,
            String eventType,
            String actor,
            String targetType,
            String result,
            LocalDate from,
            LocalDate to,
            AuditSort sort,
            SortDirection direction,
            PageQuery page) {
        Instant fromInstant = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return repository.findPage(new AuditQuery(
                normalize(keyword), normalize(eventType), normalize(actor), normalize(targetType),
                normalize(result), fromInstant, toExclusive,
                sort == null ? AuditSort.OCCURRED_AT : sort,
                direction == null ? SortDirection.DESC : direction,
                page));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum AuditSort { OCCURRED_AT, ACTOR, EVENT_TYPE, TARGET, RESULT }

    public record AuditQuery(
            String keyword,
            String eventType,
            String actor,
            String targetType,
            String result,
            Instant from,
            Instant toExclusive,
            AuditSort sort,
            SortDirection direction,
            PageQuery page) {
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
}
