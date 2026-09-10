package com.example.permissiondemo.authorization;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.authorization.AuthorityHistoryService.HistoryEvent;
import com.example.permissiondemo.authorization.AuthorityHistoryService.HistoryStatus;
import com.example.permissiondemo.authorization.AuthorityRequestService.AuthorityRequest;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestStatus;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.stereotype.Service;

/** 명시적으로 연결된 이력만 합치고, 연결 근거가 없는 과거 신청·부여 기록은 출처별로 조회한다. */
@Service
@StateBoundary
public class AuthorityHistoryQueryService {
    private final AuthorityHistoryService history;
    private final AuthorityRequestService requests;
    private final AuthorizationCatalog catalog;
    private final DirectoryService directory;
    private AuthoritySsoHistoryLinkService ssoLinks;

    public AuthorityHistoryQueryService(AuthorityHistoryService history, AuthorityRequestService requests,
            AuthorizationCatalog catalog, DirectoryService directory) {
        this.history = history;
        this.requests = requests;
        this.catalog = catalog;
        this.directory = directory;
    }

    @Autowired
    public void setSsoLinks(AuthoritySsoHistoryLinkService ssoLinks) {
        this.ssoLinks = ssoLinks;
    }

    /** 저장 상태를 변경하지 않는 통합 조회. 총 건수는 출처별 기록 수이며 업무 처리 횟수가 아니다. */
    public PageResult<HistoryRow> list(String username, HistoryStatus status, PageQuery query) {
        List<HistoryEvent> journal = history.all();
        var requestState = (AuthorityRequestService.StoredState) requests.snapshotState();
        var catalogState = (AuthorizationCatalog.StoredState) catalog.snapshotState();
        Map<String, String> userNames = new HashMap<>();
        directory.view().people().forEach(p -> userNames.put(p.username(), p.name()));
        Map<String, String> authorityNames = new HashMap<>();
        catalogState.authorities().forEach(a -> authorityNames.put(a.id(), a.name()));
        Map<AssignmentLinkKey, AuthoritySsoHistoryLinkService.Link> links = new HashMap<>();
        if (ssoLinks != null) {
            ssoLinks.list().forEach(link -> links.put(new AssignmentLinkKey(link.username(), link.organizationId(),
                    link.authorityId(), link.assignmentType(), link.assignmentSequence()), link));
        }

        List<HistoryRow> rows = new ArrayList<>();
        Set<RequestKey> coveredRequests = new HashSet<>();
        Set<AssignmentKey> coveredAssignments = new HashSet<>();
        for (HistoryEvent event : journal) {
            var link = event.assignmentSequence() == null ? null : links.get(new AssignmentLinkKey(event.username(),
                    event.organizationId(), event.authorityId(), event.assignmentType(), event.assignmentSequence()));
            rows.add(row("J:" + event.eventId(), HistorySource.JOURNAL, event.eventId(), event.username(),
                    event.organizationId(), event.authorityId(), event.assignmentType(), event.status(),
                    event.requestKind(), event.requestId(), event.assignmentSequence(), event.registeredAt(),
                    event.processedAt(), event.actor(), event.reason(), event.validFrom(), event.validTo(), userNames, authorityNames,
                    link == null ? null : link.ssoOfficeCode(), link == null ? null : link.ssoDepartmentCode()));
            if (event.requestId() != null) coveredRequests.add(new RequestKey(event.username(), event.organizationId(),
                    event.authorityId(), event.assignmentType(), event.requestId(), event.status()));
            if (event.assignmentSequence() != null) coveredAssignments.add(new AssignmentKey(event.username(), event.organizationId(),
                    event.authorityId(), event.assignmentType(), event.assignmentSequence(), event.status()));
        }

        for (AuthorityRequest request : requestState.requests()) {
            for (String authorityId : request.authorityIds()) {
                RequestKey pendingKey = requestKey(request, authorityId, HistoryStatus.PENDING);
                AuthorityAssignment pending = request.workflowVersion() >= 2
                        ? requestState.baselines().getOrDefault(request.id(), List.of()).stream()
                                .filter(a -> a.username().equals(request.username()) && a.organizationId().equals(request.organizationId())
                                        && a.authorityId().equals(authorityId) && a.type() == AssignmentType.DIRECT
                                        && a.status() == AuthorizationCatalog.AssignmentStatus.PENDING)
                                .findFirst().orElse(null)
                        : null;
                // v2 baseline 자체가 요청과 직접 신청 이력의 연결 근거다. 이름·기간 유사성으로 연결하지 않는다.
                if (pending != null) coveredAssignments.add(assignmentKey(pending));
                if (!coveredRequests.contains(pendingKey)) {
                    boolean knownPeriod = request.workflowVersion() < 2 || request.status() == RequestStatus.PENDING;
                    rows.add(row(requestRecordKey(pendingKey), HistorySource.REQUEST, null, request.username(),
                            request.organizationId(), authorityId, AssignmentType.DIRECT, HistoryStatus.PENDING,
                            request.kind(), request.id(), pending == null ? null : pending.sequence(), request.requestedAt(),
                            null, request.username(), request.reason(), knownPeriod ? request.validFrom() : null,
                            knownPeriod ? request.validTo() : null, userNames, authorityNames, null, null));
                }
                if (request.status() != RequestStatus.PENDING) {
                    HistoryStatus reviewStatus = HistoryStatus.valueOf(request.status().name());
                    RequestKey reviewKey = requestKey(request, authorityId, reviewStatus);
                    if (!coveredRequests.contains(reviewKey)) {
                        rows.add(row(requestRecordKey(reviewKey), HistorySource.REQUEST, null, request.username(),
                                request.organizationId(), authorityId, AssignmentType.DIRECT, reviewStatus, request.kind(),
                                request.id(), null, request.reviewedAt(), request.reviewedAt(), request.reviewer(),
                                request.reviewReason(), request.validFrom(), request.validTo(), userNames, authorityNames, null, null));
                    }
                }
            }
        }

        for (AuthorityAssignment assignment : catalogState.assignments()) {
            AssignmentKey key = assignmentKey(assignment);
            if (coveredAssignments.contains(key)) continue;
            rows.add(row(assignmentRecordKey(key), HistorySource.ASSIGNMENT, null, assignment.username(),
                    assignment.organizationId(), assignment.authorityId(), assignment.type(), key.status(), null, null,
                    assignment.sequence(), null, null, null, null, assignment.validFrom(), assignment.validTo(), userNames, authorityNames,
                    null, null));
        }
        return PageResult.of(rows.stream().filter(r -> username == null || username.equals(r.username()))
                .filter(r -> status == null || status == r.status()).sorted(ROW_ORDER).toList(), query);
    }

    private static final Comparator<HistoryRow> ROW_ORDER = Comparator
            .comparing(HistoryRow::registeredAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(HistoryRow::eventId, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(HistoryRow::username).thenComparing(HistoryRow::organizationId)
            .thenComparing(HistoryRow::authorityId).thenComparing(r -> r.assignmentType().name())
            .thenComparing(HistoryRow::assignmentSequence, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(HistoryRow::recordKey);

    private static HistoryRow row(String key, HistorySource source, Long eventId, String username, String organizationId,
            String authorityId, AssignmentType type, HistoryStatus status, RequestKind kind, Long requestId, Long sequence,
            Instant registeredAt, Instant processedAt, String actor, String reason, LocalDate from, LocalDate to,
            Map<String, String> userNames, Map<String, String> authorityNames, String ssoOfficeCode,
            String ssoDepartmentCode) {
        return new HistoryRow(key, source, eventId, username, organizationId, authorityId, type, status, kind, requestId,
                sequence, registeredAt, processedAt, actor, reason, from, to, userNames.get(username),
                authorityNames.get(authorityId), actor == null ? null : userNames.get(actor), ssoOfficeCode,
                ssoDepartmentCode);
    }

    private static RequestKey requestKey(AuthorityRequest request, String authorityId, HistoryStatus status) {
        return new RequestKey(request.username(), request.organizationId(), authorityId, AssignmentType.DIRECT, request.id(), status);
    }

    private static AssignmentKey assignmentKey(AuthorityAssignment assignment) {
        return new AssignmentKey(assignment.username(), assignment.organizationId(), assignment.authorityId(), assignment.type(),
                assignment.sequence(), HistoryStatus.valueOf(assignment.status().name()));
    }

    private static String requestRecordKey(RequestKey key) {
        return "R:" + encode(key.username()) + ":" + encode(key.organizationId()) + ":" + encode(key.authorityId())
                + ":" + key.type() + ":" + key.requestId() + ":" + key.status();
    }

    private static String assignmentRecordKey(AssignmentKey key) {
        return "A:" + encode(key.username()) + ":" + encode(key.organizationId()) + ":" + encode(key.authorityId())
                + ":" + key.type() + ":" + key.sequence() + ":" + key.status();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record RequestKey(String username, String organizationId, String authorityId, AssignmentType type,
            long requestId, HistoryStatus status) { }
    private record AssignmentKey(String username, String organizationId, String authorityId, AssignmentType type,
            long sequence, HistoryStatus status) { }
    private record AssignmentLinkKey(String username, String organizationId, String authorityId, AssignmentType type,
            long sequence) { }
    public enum HistorySource { JOURNAL, REQUEST, ASSIGNMENT }

    public record HistoryRow(String recordKey, HistorySource source, Long eventId, String username, String organizationId,
            String authorityId, AssignmentType assignmentType, HistoryStatus status, RequestKind requestKind, Long requestId,
            Long assignmentSequence, Instant registeredAt, Instant processedAt, String actor, String reason,
            LocalDate validFrom, LocalDate validTo, String userName, String authorityName, String actorName,
            String ssoOfficeCode, String ssoDepartmentCode) { }
}
