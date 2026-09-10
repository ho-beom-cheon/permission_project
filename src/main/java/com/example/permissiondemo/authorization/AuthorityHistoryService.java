package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.authorization.AuthorityRequestService.AuthorityRequest;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestStatus;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 신규 권한 신청·처리 이력을 보존한다. 기존 부여 이력의 알 수 없는 처리 시각은 추정하지 않는다. */
@Service
@StateBoundary
public class AuthorityHistoryService implements StateParticipant {
    private final Clock clock;
    private final CurrentUserContext current;
    private final List<HistoryEvent> events = new ArrayList<>();
    private long sequence;
    private AuthoritySsoHistoryLinkService ssoLinks;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSsoLinks(AuthoritySsoHistoryLinkService ssoLinks) { this.ssoLinks = ssoLinks; }

    public AuthorityHistoryService(Clock clock, CurrentUserContext current) {
        this.clock = clock;
        this.current = current;
    }

    public synchronized void recordRequest(AuthorityRequest request, List<AuthorityAssignment> assignments) {
        if (ssoLinks != null) assignments.forEach(a -> ssoLinks.capture(a, request.requestedAt(), null));
        for (String authorityId : request.authorityIds()) {
            Long assignmentSequence = assignments.stream().filter(a -> a.authorityId().equals(authorityId))
                    .map(AuthorityAssignment::sequence).findFirst().orElse(null);
            append(request.username(), request.organizationId(), authorityId, AssignmentType.DIRECT,
                    HistoryStatus.PENDING, request.kind(), request.id(), assignmentSequence, request.requestedAt(), null,
                    request.username(), request.reason(), request.validFrom(), request.validTo());
        }
    }

    public synchronized void recordReview(AuthorityRequest request, List<AuthorityAssignment> assignments) {
        // 심사는 신청 당시 연결을 승계한다. 뒤늦게 등록된 현재 소속으로 과거 신청을 보정하지 않는다.
        if (ssoLinks != null) {
            for (AuthorityAssignment assignment : assignments) {
                events.stream().filter(e -> e.status() == HistoryStatus.PENDING
                        && java.util.Objects.equals(e.requestId(), request.id())
                        && e.username().equals(request.username()) && e.organizationId().equals(request.organizationId())
                        && e.authorityId().equals(assignment.authorityId()) && e.assignmentType() == AssignmentType.DIRECT)
                        .map(HistoryEvent::assignmentSequence).filter(java.util.Objects::nonNull).findFirst()
                        .ifPresent(prior -> ssoLinks.capture(assignment, request.reviewedAt(), prior));
            }
        }
        HistoryStatus status = request.status() == RequestStatus.APPROVED
                ? HistoryStatus.APPROVED : HistoryStatus.REJECTED;
        for (String authorityId : request.authorityIds()) {
            Long assignmentSequence = assignments.stream().filter(a -> a.authorityId().equals(authorityId))
                    .map(AuthorityAssignment::sequence).findFirst().orElse(null);
            append(request.username(), request.organizationId(), authorityId, AssignmentType.DIRECT,
                    status, request.kind(), request.id(), assignmentSequence, request.reviewedAt(), request.reviewedAt(),
                    request.reviewer(), request.reviewReason(), request.validFrom(), request.validTo());
        }
    }

    public synchronized void recordAssignment(AuthorityAssignment assignment, RequestKind kind,
            Long requestId, String actor, String reason) {
        Instant now = Instant.now(clock);
        if (ssoLinks != null) ssoLinks.capture(assignment, now,
                assignment.status() == AuthorizationCatalog.AssignmentStatus.REVOKED ? assignment.sequence() - 1 : null);
        append(assignment.username(), assignment.organizationId(), assignment.authorityId(), assignment.type(),
                HistoryStatus.valueOf(assignment.status().name()), kind, requestId, assignment.sequence(),
                now, now, actor == null ? current.find().map(CurrentUserContext.CurrentUser::username).orElse("SYSTEM") : actor,
                reason, assignment.validFrom(), assignment.validTo());
    }

    private void append(String username, String organizationId, String authorityId, AssignmentType type,
            HistoryStatus status, RequestKind kind, Long requestId, Long assignmentSequence,
            Instant registeredAt, Instant processedAt, String actor, String reason,
            LocalDate validFrom, LocalDate validTo) {
        events.add(new HistoryEvent(++sequence, username, organizationId, authorityId, type, status, kind,
                requestId, assignmentSequence, registeredAt, processedAt, actor, reason, validFrom, validTo));
    }

    /** 사용자별 과거 조직까지 포함하며 원본과 같이 등록시각·이력순번의 역순으로 조회한다. */
    public synchronized PageResult<HistoryEvent> list(String username, HistoryStatus status, PageQuery query) {
        return PageResult.of(events.stream().filter(e -> username == null || username.equals(e.username()))
                .filter(e -> status == null || status == e.status())
                .sorted(Comparator.comparing(HistoryEvent::registeredAt).thenComparingLong(HistoryEvent::eventId).reversed())
                .toList(), query);
    }

    public synchronized List<HistoryEvent> all() { return List.copyOf(events); }

    /** SSO 소속별 최신 종결행의 사유만 고치고 처리 메타데이터와 이력 수는 유지한다. */
    public synchronized HistoryEvent correctLatestReason(String username, String authorityId,
            String ssoOfficeCode, String ssoDepartmentCode, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("반려 또는 회수 사유를 입력해 주세요.");
        }
        if (ssoLinks == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId);
        AuthoritySsoHistoryLinkService.Link latest = ssoLinks.list().stream()
                .filter(link -> link.assignmentType() == AssignmentType.DIRECT)
                .filter(link -> Objects.equals(username, link.username()))
                .filter(link -> Objects.equals(authorityId, link.authorityId()))
                .filter(link -> Objects.equals(ssoOfficeCode, link.ssoOfficeCode()))
                .filter(link -> Objects.equals(ssoDepartmentCode, link.ssoDepartmentCode()))
                .max(Comparator.comparing(AuthoritySsoHistoryLinkService.Link::registeredAt)
                        .thenComparingLong(AuthoritySsoHistoryLinkService.Link::assignmentSequence))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId));
        for (int index = 0; index < events.size(); index++) {
            HistoryEvent event = events.get(index);
            if (!event.username().equals(latest.username())
                    || !event.organizationId().equals(latest.organizationId())
                    || !event.authorityId().equals(latest.authorityId())
                    || event.assignmentType() != AssignmentType.DIRECT
                    || !Objects.equals(event.assignmentSequence(), latest.assignmentSequence())) continue;
            if (event.status() != HistoryStatus.REJECTED && event.status() != HistoryStatus.REVOKED) {
                throw new ApiException(ErrorCode.CONFLICT, "반려 또는 회수된 최신 권한만 사유를 수정할 수 있습니다.");
            }
            HistoryEvent corrected = new HistoryEvent(event.eventId(), event.username(), event.organizationId(),
                    event.authorityId(), event.assignmentType(), event.status(), event.requestKind(), event.requestId(),
                    event.assignmentSequence(), event.registeredAt(), event.processedAt(), event.actor(), reason,
                    event.validFrom(), event.validTo());
            events.set(index, corrected);
            return corrected;
        }
        throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId);
    }

    public enum HistoryStatus { PENDING, APPROVED, REJECTED, REVOKED }

    public record HistoryEvent(long eventId, String username, String organizationId, String authorityId,
            AssignmentType assignmentType, HistoryStatus status, RequestKind requestKind, Long requestId,
            Long assignmentSequence, Instant registeredAt, Instant processedAt, String actor, String reason,
            LocalDate validFrom, LocalDate validTo) { }

    @Override public String stateKey() { return "authority-history"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public synchronized Object snapshotState() { return new StoredState(List.copyOf(events), sequence); }
    @Override public synchronized void restoreState(Object raw) {
        StoredState state = (StoredState) raw;
        events.clear(); events.addAll(state.events()); sequence = state.sequence();
    }
    public record StoredState(List<HistoryEvent> events, long sequence) { }
}
