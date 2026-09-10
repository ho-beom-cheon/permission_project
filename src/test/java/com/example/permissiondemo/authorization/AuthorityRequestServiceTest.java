package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityClassification;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorityRequestService.Decision;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestStatus;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.InboxService;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 원본 데이터 없이 가상 사용자로 신청·변경 승인·동시 변경·일괄 처리 경계를 검증한다. */
class AuthorityRequestServiceTest {
    private AuthorizationCatalog catalog;
    private AuthorityRequestService requests;
    private EffectiveAuthorityService effective;
    private final LocalDate from = LocalDate.of(2026, 9, 1);
    private final LocalDate to = LocalDate.of(2027, 9, 1);

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        requests = new AuthorityRequestService(catalog, new AuditEventService(), clock);
        effective = new EffectiveAuthorityService(catalog, clock);
    }

    @Test
    void pendingRequestDoesNotGrantAuthorityAndApprovalIsNotRepeatable() {
        var request = requests.submit("viewer", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"), from, to, "가상 업무 담당");
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly("AUTH_VIEWER");
        requests.review("admin", List.of(request.id()), Decision.APPROVE, "확인");
        assertThat(effective.findEffectiveAuthorityIds("viewer")).contains("AUTH_CONTENT_MANAGER", "AUTH_VIEWER");
        assertThatThrownBy(() -> requests.review("admin", List.of(request.id()), Decision.APPROVE, "확인"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void changeApprovalRevokesOnlySameSystemDirectAuthorities() {
        catalog.saveAuthority(new AuthorityDefinition("OTHER_READ", "가상 타 시스템", true, "OTHER", null, ""));
        catalog.saveAssignment("delegate", "OTHER_READ", AssignmentType.DIRECT, from, to, null);
        var request = requests.submit("delegate", RequestKind.CHANGE, Set.of("AUTH_CONTENT_MANAGER"), from, to, "업무 변경");
        requests.review("admin", List.of(request.id()), Decision.APPROVE, "");
        assertThat(effective.findEffectiveAuthorityIds("delegate"))
                .contains("AUTH_CONTENT_MANAGER", "OTHER_READ").doesNotContain("AUTH_VIEWER");
        assertThat(catalog.assignmentsFor("delegate")).anyMatch(item -> item.type() == AssignmentType.DELEGATED
                && item.isEffectiveOn(from));
    }

    @Test
    void rejectionRequiresReasonAndDoesNotChangeEffectiveAuthority() {
        var request = requests.submit("viewer", RequestKind.CHANGE, Set.of("AUTH_CONTENT_MANAGER"), from, to, "업무 변경");
        assertThatThrownBy(() -> requests.review("admin", List.of(request.id()), Decision.REJECT, " "))
                .isInstanceOf(IllegalArgumentException.class);
        requests.review("admin", List.of(request.id()), Decision.REJECT, "담당 업무 재확인 필요");
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly("AUTH_VIEWER");
        var history = requests.list("viewer", RequestStatus.REJECTED, PageQuery.of(0, 20));
        assertThat(history.content()).singleElement().satisfies(item -> {
            assertThat(item.reviewer()).isEqualTo("admin");
            assertThat(item.reviewReason()).isEqualTo("담당 업무 재확인 필요");
        });
    }

    @Test
    void oneInvalidRequestPreventsEveryApprovalInBatch() {
        var first = requests.submit("viewer", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"), from, to, "신규 업무");
        var second = requests.submit("expired", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"), from, to, "신규 업무");
        catalog.saveAssignment("expired", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT, from, to, null);
        assertThatThrownBy(() -> requests.review("admin", List.of(first.id(), second.id()), Decision.APPROVE, ""))
                .isInstanceOf(ApiException.class);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).doesNotContain("AUTH_CONTENT_MANAGER");
        assertThat(requests.list(null, RequestStatus.PENDING, PageQuery.of(0, 20)).totalElements()).isEqualTo(2);
    }

    @Test
    void staleChangeRequestCannotOverwriteNewlyGrantedAuthority() {
        var request = requests.submit("viewer", RequestKind.CHANGE, Set.of("AUTH_CONTENT_MANAGER"), from, to, "업무 변경");
        catalog.saveAssignment("viewer", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT, from, to, null);
        assertThatThrownBy(() -> requests.review("admin", List.of(request.id()), Decision.APPROVE, ""))
                .isInstanceOf(ApiException.class);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).contains("AUTH_CONTENT_MANAGER", "AUTH_VIEWER");
    }

    @Test
    void duplicatePendingAndCrossSystemRequestsAreRejected() {
        catalog.saveAuthority(new AuthorityDefinition("OTHER_READ", "가상 타 시스템", true, "OTHER", null, ""));
        assertThatThrownBy(() -> requests.submit("viewer", RequestKind.GRANT,
                Set.of("AUTH_CONTENT_MANAGER", "OTHER_READ"), from, to, "혼합 신청"))
                .isInstanceOf(IllegalArgumentException.class);
        requests.submit("viewer", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"), from, to, "신규 업무");
        assertThatThrownBy(() -> requests.submit("viewer", RequestKind.CHANGE,
                Set.of("AUTH_CONTENT_MANAGER"), from, to, "중복 신청"))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @CsvSource({"2026-09-02,2026-09-10", "2026-09-01,2026-09-02", "2026-09-02,2026-09-02"})
    void currentDirectGrantCannotBeRequestedAgainIncludingPeriodBoundaries(String start, String end) {
        catalog.saveAssignment("viewer", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT,
                LocalDate.parse(start), LocalDate.parse(end), null);
        assertThatThrownBy(() -> requests.submit("viewer", RequestKind.GRANT,
                Set.of("AUTH_CONTENT_MANAGER"), from, to, "중복 신청"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @ParameterizedTest
    @CsvSource({"2026-08-01,2026-09-01", "2026-09-03,2026-10-01"})
    void expiredOrFutureDirectGrantDoesNotBlockRequest(String start, String end) {
        catalog.saveAssignment("viewer", "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT,
                LocalDate.parse(start), LocalDate.parse(end), null);
        assertThat(requests.submit("viewer", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"),
                from, to, "권한 신청").status()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void latestRevocationAllowsRequestDespiteOlderEffectiveApproval() {
        catalog.revokeAssignment("viewer", "AUTH_VIEWER", AssignmentType.DIRECT);
        assertThat(requests.submit("viewer", RequestKind.GRANT, Set.of("AUTH_VIEWER"),
                from, to, "회수 후 재신청").status()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void delegatedAndPreviousOrganizationHoldingsDoNotBlockDirectRequest() {
        assertThat(requests.submit("delegate", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"),
                from, to, "직접 권한 신청").status()).isEqualTo(RequestStatus.PENDING);
        assertThat(requests.submit("moved", RequestKind.GRANT, Set.of("AUTH_SYSTEM_ADMIN"),
                from, to, "현재 조직 권한 신청").status()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void changeRequiresOneNotCurrentlyHeldTarget() {
        assertThatThrownBy(() -> requests.submit("viewer", RequestKind.CHANGE,
                Set.of("AUTH_VIEWER", "AUTH_CONTENT_MANAGER"), from, to, "업무 권한 변경"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.submit("viewer", RequestKind.CHANGE,
                Set.of("AUTH_VIEWER"), from, to, "현재 보유 권한 변경"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void oneAlreadyHeldAuthorityRejectsWholeRequestWithoutStateChanges() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
        var audit = new AuditEventService();
        var inbox = new InboxService(catalog, null, audit, clock);
        requests = new AuthorityRequestService(catalog, audit, clock, inbox);
        Object beforeRequests = requests.snapshotState();
        Object beforeCatalog = catalog.snapshotState();
        Object beforeAudit = audit.snapshotState();
        Object beforeInbox = inbox.snapshotState();

        assertThatThrownBy(() -> requests.submit("viewer", RequestKind.GRANT,
                Set.of("AUTH_CONTENT_MANAGER", "AUTH_VIEWER"), from, to, "일괄 권한 신청"))
                .isInstanceOf(ApiException.class);

        assertThat(requests.snapshotState()).isEqualTo(beforeRequests);
        assertThat(catalog.snapshotState()).isEqualTo(beforeCatalog);
        assertThat(audit.snapshotState()).isEqualTo(beforeAudit);
        assertThat(inbox.snapshotState()).isEqualTo(beforeInbox);
        assertThat(requests.submit("viewer", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"),
                from, to, "미보유 권한만 신청").id()).isEqualTo(1L);
    }

    @Test
    void classificationPreventsCyclesCrossSystemLinksAndDeletingUsedNodes() {
        catalog.saveClassification(new AuthorityClassification("ROOT", null, "최상위", "INFO", true));
        catalog.saveClassification(new AuthorityClassification("CHILD", "ROOT", "하위", "INFO", true));
        assertThatThrownBy(() -> catalog.saveClassification(new AuthorityClassification("ROOT", "CHILD", "순환", "INFO", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.saveClassification(new AuthorityClassification("OTHER", "ROOT", "다른 시스템", "OTHER", true)))
                .isInstanceOf(ApiException.class);
        catalog.saveAuthority(new AuthorityDefinition("CLASSIFIED", "분류 연결 권한", true, "INFO", "CHILD", "설명"));
        assertThatThrownBy(() -> catalog.deleteClassification("CHILD")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> catalog.saveClassification(new AuthorityClassification("CHILD", "ROOT", "하위", "INFO", false)))
                .isInstanceOf(ApiException.class);
        assertThat(catalog.findAuthority("CLASSIFIED").orElseThrow().classificationId()).isEqualTo("CHILD");
    }
}
