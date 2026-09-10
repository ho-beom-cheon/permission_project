package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.authorization.AuthorityRequestService.AuthorityRequest;
import com.example.permissiondemo.authorization.AuthorityRequestService.Decision;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestStatus;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 원본 최신 상태·처리일·변경승인 규칙과 기존 저장 신청의 호환 경계를 검증한다. */
class AuthoritySourceWorkflowTest {
    private final MovingClock clock = new MovingClock();
    private AuthorizationCatalog catalog;
    private AuthorityRequestService requests;
    private EffectiveAuthorityService effective;

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        requests = new AuthorityRequestService(catalog, new AuditEventService(), clock);
        effective = new EffectiveAuthorityService(catalog, clock);
    }

    @Test
    void futureApprovalNeverReappearsBehindPendingOrRejectedAndProcessingUsesSourceDates() {
        catalog.saveAssignment("viewer", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT,
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 10, 1), null);
        var request = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        assertThat(request.workflowVersion()).isEqualTo(2);
        assertThat(request.validFrom()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(request.validTo()).isEqualTo(LocalDate.of(9999, 12, 31));
        clock.day("2026-09-04");
        assertThat(effective.findEffectiveAuthorityIds("viewer")).doesNotContain("AUTH_CONTENT_MANAGER");
        var rejected = requests.review("admin", List.of(request.id()), Decision.REJECT, "업무 재확인").get(0);
        assertThat(rejected.validFrom()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(rejected.validTo()).isEqualTo(rejected.validFrom());
        assertThat(latest("viewer", "HQ", "AUTH_CONTENT_MANAGER").status()).isEqualTo(AssignmentStatus.REJECTED);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).doesNotContain("AUTH_CONTENT_MANAGER");
        var again = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        clock.day("2026-09-05");
        var approved = requests.review("admin", List.of(again.id()), Decision.APPROVE, "").get(0);
        assertThat(approved.validFrom()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(approved.validTo()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).contains("AUTH_CONTENT_MANAGER");
    }

    @Test
    void batchChangesRevokeExistingEffectiveRightsOnceBeforeAllSelectedApprovals() {
        for (String id : List.of("NEW_READ", "FUTURE_READ", "EXPIRED_READ")) {
            catalog.saveAuthority(new AuthorityDefinition(id, id, true, "INFO", null, ""));
        }
        catalog.saveAssignment("viewer", "FUTURE_READ", AssignmentType.DIRECT,
                LocalDate.of(2026, 9, 3), LocalDate.of(2027, 1, 1), null);
        catalog.saveAssignment("viewer", "EXPIRED_READ", AssignmentType.DIRECT,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), null);
        var grant = submit("viewer", RequestKind.GRANT, "NEW_READ");
        var firstChange = submit("viewer", RequestKind.CHANGE, "AUTH_CONTENT_MANAGER");
        var secondChange = submit("viewer", RequestKind.CHANGE, "AUTH_SYSTEM_ADMIN");
        requests.review("admin", List.of(grant.id(), firstChange.id(), secondChange.id()), Decision.APPROVE, "");
        assertThat(effective.findEffectiveAuthorityIds("viewer"))
                .containsExactlyInAnyOrder("NEW_READ", "AUTH_CONTENT_MANAGER", "AUTH_SYSTEM_ADMIN");
        assertThat(latest("viewer", "HQ", "AUTH_VIEWER").status()).isEqualTo(AssignmentStatus.REVOKED);
        assertThat(latest("viewer", "HQ", "AUTH_VIEWER").validTo()).isEqualTo(LocalDate.now(clock));
        assertThat(latest("viewer", "HQ", "FUTURE_READ").sequence()).isEqualTo(1);
        assertThat(latest("viewer", "HQ", "EXPIRED_READ").sequence()).isEqualTo(1);
        assertThat(catalog.assignmentsFor("viewer").stream().filter(a -> a.status() == AssignmentStatus.REVOKED).toList()).hasSize(1);
    }

    @Test
    void supersededPendingRejectsEntireReviewBeforeAnyOtherStateChanges() {
        var first = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        var second = submit("expired", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        catalog.saveAssignment("expired", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT,
                LocalDate.now(clock), LocalDate.now(clock).plusDays(4), null);
        Object beforeCatalog = catalog.snapshotState();
        Object beforeRequests = requests.snapshotState();
        assertThatThrownBy(() -> requests.review("admin", List.of(first.id(), second.id()), Decision.REJECT, "반려"))
                .isInstanceOf(ApiException.class);
        assertThat(catalog.snapshotState()).isEqualTo(beforeCatalog);
        assertThat(requests.snapshotState()).isEqualTo(beforeRequests);
        assertThat(effective.findEffectiveAuthorityIds("expired")).contains("AUTH_CONTENT_MANAGER");
    }

    @Test
    void movedUserCanRejectOriginalOrganizationPendingWithoutWritingIntoNewOrganization() {
        var request = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        catalog.saveUserProfile("viewer", "BRANCH_A", true);
        assertThatThrownBy(() -> requests.review("admin", List.of(request.id()), Decision.APPROVE, ""))
                .isInstanceOf(ApiException.class);
        requests.review("admin", List.of(request.id()), Decision.REJECT, "조직 이동");
        assertThat(latest("viewer", "HQ", "AUTH_CONTENT_MANAGER").status()).isEqualTo(AssignmentStatus.REJECTED);
        assertThat(catalog.assignmentsFor("viewer")).noneMatch(a -> a.organizationId().equals("BRANCH_A"));
    }

    @Test
    void administratorCannotGrantOrRevokePendingDirectRightButUnrelatedGrantDoesNotInvalidateRequest() {
        var request = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        AdminService admin = new AdminService(catalog, effective, new AuditEventService());
        assertThatThrownBy(() -> admin.assignAuthority("viewer", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT,
                LocalDate.now(clock), LocalDate.now(clock).plusDays(5), null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> admin.revokeAuthority("viewer", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT, "회수 사유"))
                .isInstanceOf(ApiException.class);
        admin.assignAuthority("viewer", "AUTH_SYSTEM_ADMIN", AssignmentType.DIRECT,
                LocalDate.now(clock), LocalDate.now(clock).plusDays(5), null);
        requests.review("admin", List.of(request.id()), Decision.APPROVE, "");
        assertThat(effective.findEffectiveAuthorityIds("viewer")).contains("AUTH_SYSTEM_ADMIN", "AUTH_CONTENT_MANAGER");
    }

    @Test
    void legacyChangeRetainsOldPeriodAndMultiTargetBehaviorAndBlocksMixedNewRequests() {
        LocalDate oldFrom = LocalDate.of(2026, 9, 1);
        LocalDate oldTo = LocalDate.of(2027, 9, 1);
        AuthorityRequest old = new AuthorityRequest(1L, "viewer", "HQ", "INFO", RequestKind.CHANGE,
                List.of("AUTH_VIEWER", "AUTH_CONTENT_MANAGER"), oldFrom, oldTo, "이전 저장 신청",
                RequestStatus.PENDING, clock.instant(), null, null, null);
        List<AuthorityAssignment> baseline = catalog.assignmentsFor("viewer").stream()
                .filter(a -> a.type() == AssignmentType.DIRECT).toList();
        requests.restoreState(new AuthorityRequestService.StoredState(List.of(old), Map.of(1L, baseline), 1L));
        assertThatThrownBy(() -> submit("viewer", RequestKind.GRANT, "AUTH_SYSTEM_ADMIN")).isInstanceOf(ApiException.class);
        AuthorityRequest approved = requests.review("admin", List.of(1L), Decision.APPROVE, "").get(0);
        assertThat(approved.workflowVersion()).isEqualTo(1);
        assertThat(approved.validFrom()).isEqualTo(oldFrom);
        assertThat(approved.validTo()).isEqualTo(oldTo);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactlyInAnyOrder("AUTH_VIEWER", "AUTH_CONTENT_MANAGER");
    }

    @Test
    void v2WithoutItsPendingBaselineCannotBeReviewed() {
        var request = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        requests.restoreState(new AuthorityRequestService.StoredState(List.of(request), Map.of(), request.id()));
        assertThatThrownBy(() -> requests.review("admin", List.of(request.id()), Decision.REJECT, "반려"))
                .isInstanceOf(ApiException.class);
        assertThat(requests.list("viewer", RequestStatus.PENDING, PageQuery.of(0, 20)).totalElements()).isEqualTo(1);
    }

    private AuthorityRequest submit(String username, RequestKind kind, String authorityId) {
        // 신규 요청 기간은 클라이언트 입력과 관계없이 원본 신청일~9999-12-31을 사용한다.
        return requests.submit(username, kind, Set.of(authorityId), LocalDate.of(2000, 1, 1), null, "업무 신청");
    }

    private AuthorityAssignment latest(String username, String organizationId, String authorityId) {
        return catalog.assignmentsFor(username).stream().filter(a -> a.type() == AssignmentType.DIRECT
                        && a.organizationId().equals(organizationId) && a.authorityId().equals(authorityId))
                .max(Comparator.comparingLong(AuthorityAssignment::sequence)).orElseThrow();
    }

    private static final class MovingClock extends Clock {
        private Instant value = Instant.parse("2026-09-02T00:00:00Z");
        void day(String day) { value = Instant.parse(day + "T00:00:00Z"); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
        @Override public Instant instant() { return value; }
    }
}
