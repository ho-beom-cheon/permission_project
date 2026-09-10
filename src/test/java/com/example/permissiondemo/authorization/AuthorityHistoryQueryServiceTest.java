package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorityHistoryQueryService.HistoryRow;
import com.example.permissiondemo.authorization.AuthorityHistoryQueryService.HistorySource;
import com.example.permissiondemo.authorization.AuthorityHistoryService.HistoryEvent;
import com.example.permissiondemo.authorization.AuthorityHistoryService.HistoryStatus;
import com.example.permissiondemo.authorization.AuthorityRequestService.AuthorityRequest;
import com.example.permissiondemo.authorization.AuthorityRequestService.Decision;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestStatus;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 연결 키가 있는 경우만 중복을 제거하고 저장되지 않은 과거 메타데이터는 추정하지 않는다. */
class AuthorityHistoryQueryServiceTest {
    private static final String USER = "history_user";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2027, 9, 1);
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private AuthorizationCatalog catalog;
    private AuthorityRequestService requests;
    private AuthorityHistoryService history;
    private AuthorityHistoryQueryService query;
    private DirectoryService directory;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        catalog = new AuthorizationCatalog();
        catalog.saveUserProfile(USER, "HQ", true);
        var audit = new AuditEventService();
        requests = new AuthorityRequestService(catalog, audit, clock);
        var current = mock(CurrentUserContext.class);
        when(current.find()).thenReturn(Optional.empty());
        history = new AuthorityHistoryService(clock, current);
        directory = new DirectoryService(catalog, audit);
        query = new AuthorityHistoryQueryService(history, requests, catalog, directory);
    }

    @Test
    void journalSuppressesOnlyItsExplicitRequestAndAssignmentRecords() {
        requests.setHistory(history);
        var request = requests.submit(USER, RequestKind.GRANT, Set.of("AUTH_VIEWER", "AUTH_CONTENT_MANAGER"), FROM, TO, "신청");
        requests.review("admin", List.of(request.id()), Decision.APPROVE, "승인");
        assertThat(rows()).hasSize(4).allSatisfy(row -> {
            assertThat(row.source()).isEqualTo(HistorySource.JOURNAL);
            assertThat(row.eventId()).isNotNull();
            assertThat(row.assignmentSequence()).isNotNull();
        });
        assertThat(rows()).extracting(HistoryRow::eventId).containsExactly(4L, 3L, 2L, 1L);
    }

    @Test
    void unlinkedLegacyRequestsAndAssignmentsStaySeparateAcrossTypeAndOrganization() {
        AuthorityRequest request = new AuthorityRequest(7L, USER, "HQ", "INFO", RequestKind.GRANT,
                List.of("AUTH_VIEWER"), FROM, TO, "과거 신청", RequestStatus.APPROVED,
                NOW.minusSeconds(60), "admin", NOW, "과거 승인");
        requests.restoreState(new AuthorityRequestService.StoredState(List.of(request), Map.of(), 7L));
        catalog.saveAssignment(USER, "AUTH_VIEWER", AssignmentType.DIRECT, FROM, TO, null);
        catalog.saveAssignment(USER, "AUTH_VIEWER", AssignmentType.DELEGATED, FROM, TO, "manager");
        catalog.saveUserProfile(USER, "BRANCH_A", true);
        catalog.saveAssignment(USER, "AUTH_VIEWER", AssignmentType.DIRECT, FROM, TO, null);

        var result = rows();
        assertThat(result).hasSize(5);
        assertThat(result.subList(0, 2)).allSatisfy(row -> assertThat(row.source()).isEqualTo(HistorySource.REQUEST));
        assertThat(result.stream().filter(row -> row.source() == HistorySource.ASSIGNMENT).toList())
                .hasSize(3).allSatisfy(row -> {
                    assertThat(row.registeredAt()).isNull();
                    assertThat(row.processedAt()).isNull();
                    assertThat(row.actor()).isNull();
                    assertThat(row.actorName()).isNull();
                    assertThat(row.reason()).isNull();
                    assertThat(row.requestId()).isNull();
                });
        assertThat(result).extracting(HistoryRow::recordKey).doesNotHaveDuplicates();
        assertThat(result.stream().filter(row -> row.status() == HistoryStatus.PENDING).findFirst().orElseThrow().validFrom())
                .isEqualTo(FROM);
    }

    @Test
    void v2PendingBaselineLinksAssignmentButCompletedUnlinkedRequestDoesNotInventOriginalPeriod() {
        var request = requests.submit(USER, RequestKind.GRANT, Set.of("AUTH_VIEWER"), FROM, TO, "신청");
        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo(HistorySource.REQUEST);
            assertThat(row.assignmentSequence()).isEqualTo(1L);
            assertThat(row.validFrom()).isEqualTo(LocalDate.of(2026, 9, 3));
        });
        requests.review("admin", List.of(request.id()), Decision.REJECT, "반려");
        assertThat(rows()).hasSize(4);
        assertThat(rows().stream().filter(row -> row.source() == HistorySource.REQUEST && row.status() == HistoryStatus.PENDING)
                .findFirst().orElseThrow()).satisfies(row -> {
                    assertThat(row.validFrom()).isNull();
                    assertThat(row.validTo()).isNull();
                    assertThat(row.assignmentSequence()).isNull();
                    assertThat(row.registeredAt()).isEqualTo(NOW);
                });
        assertThat(rows().stream().filter(row -> row.source() == HistorySource.REQUEST && row.status() == HistoryStatus.REJECTED)
                .findFirst().orElseThrow().validTo()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void sameSequenceWithConflictingStatusIsNotHiddenByJournal() {
        catalog.saveAssignment(USER, "AUTH_VIEWER", AssignmentType.DIRECT, FROM, TO, null);
        history.restoreState(new AuthorityHistoryService.StoredState(List.of(new HistoryEvent(1L, USER, "HQ",
                "AUTH_VIEWER", AssignmentType.DIRECT, HistoryStatus.REVOKED, null, null, 1L,
                NOW, NOW, "admin", "상태가 다른 기록", FROM, TO)), 1L));
        assertThat(rows()).hasSize(2).extracting(HistoryRow::status)
                .containsExactly(HistoryStatus.REVOKED, HistoryStatus.APPROVED);
    }

    @Test
    void readsCurrentNamesAndFilteringDoesNotChangeStoredState() {
        requests.setHistory(history);
        var request = requests.submit(USER, RequestKind.GRANT, Set.of("AUTH_VIEWER"), FROM, TO, "신청");
        requests.review("admin", List.of(request.id()), Decision.APPROVE, "승인");
        rename(USER, "변경된 사용자명");
        rename("admin", "현재 처리자명");
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition("AUTH_VIEWER", "현재 권한명", true, "INFO", null, ""));
        Object beforeCatalog = catalog.snapshotState();
        Object beforeRequests = requests.snapshotState();
        Object beforeHistory = history.snapshotState();
        Object beforeDirectory = directory.snapshotState();

        var page = query.list(USER, HistoryStatus.APPROVED, PageQuery.of(0, 1));
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row.userName()).isEqualTo("변경된 사용자명");
            assertThat(row.actorName()).isEqualTo("현재 처리자명");
            assertThat(row.authorityName()).isEqualTo("현재 권한명");
        });
        assertThat(query.list("viewer", null, PageQuery.of(0, 20)).content()).allMatch(row -> row.username().equals("viewer"));
        assertThat(catalog.snapshotState()).isEqualTo(beforeCatalog);
        assertThat(requests.snapshotState()).isEqualTo(beforeRequests);
        assertThat(history.snapshotState()).isEqualTo(beforeHistory);
        assertThat(directory.snapshotState()).isEqualTo(beforeDirectory);
    }

    private List<HistoryRow> rows() { return query.list(USER, null, PageQuery.of(0, 100)).content(); }
    private void rename(String username, String name) {
        var person = directory.person(username);
        directory.savePerson(new DirectoryService.Person(person.username(), name, person.officeCode(), person.departmentCode(),
                person.rankCode(), person.telephone(), person.mobile(), person.email(), person.jobDescription(), person.active(), person.regionIds()));
    }
}
