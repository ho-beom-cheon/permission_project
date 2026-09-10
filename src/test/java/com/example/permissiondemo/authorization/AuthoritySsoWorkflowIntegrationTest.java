package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus;
import com.example.permissiondemo.authorization.AuthorityRequestService.Decision;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService.Scope;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** 사용자별 명시 전환 이후 요청은 SSO소속별로 처리하며 이전 로컬 조직 규칙과 혼합하지 않는다. */
@SpringBootTest(classes = PermissionDemoApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthoritySsoWorkflowIntegrationTest {
    private static final Scope A = new Scope("WF_SSO_A", "WF_DEPT_A");
    private static final Scope B = new Scope("WF_SSO_B", "WF_DEPT_B");
    @Autowired private AuthorizationCatalog catalog;
    @Autowired private AuthorityRequestService requests;
    @Autowired private SsoAuthorityScopeService scopes;
    @Autowired private OrganizationMappingService mappings;
    @Autowired private UserSsoOrganizationService affiliations;
    @Autowired private EffectiveAuthorityService effective;
    @Autowired private Clock clock;

    @Test
    void approvalUsesOriginalSsoAfterBothSsoAndLocalOrganizationMove() {
        enable("expired", A);
        var first = submit("expired", RequestKind.GRANT, "AUTH_VIEWER");
        assertThat(first.workflowVersion()).isEqualTo(3);
        assertThat(first.ssoOfficeCode()).isEqualTo(A.ssoOfficeCode());
        move("expired", B);
        catalog.saveUserProfile("expired", "BRANCH_A", true);
        var second = submit("expired", RequestKind.GRANT, "AUTH_VIEWER");
        requests.review("admin", List.of(first.id()), Decision.APPROVE, "이전 소속 신청 승인");
        assertThat(latest("expired", A, "AUTH_VIEWER").status()).isEqualTo(AssignmentStatus.APPROVED);
        assertThat(latest("expired", A, "AUTH_VIEWER").organizationId()).isEqualTo("HQ");
        assertThat(latest("expired", B, "AUTH_VIEWER").status()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(effective.findEffectiveAuthorityIds("expired")).doesNotContain("AUTH_VIEWER");
        requests.review("admin", List.of(second.id()), Decision.REJECT, "현재 소속 반려");
        assertThat(effective.findEffectiveAuthorityIds("expired")).doesNotContain("AUTH_VIEWER");
        move("expired", A);
        assertThat(effective.findEffectiveAuthorityIds("expired")).contains("AUTH_VIEWER");
        assertThatThrownBy(() -> submit("expired", RequestKind.GRANT, "AUTH_VIEWER")).isInstanceOf(ApiException.class);
    }

    @Test
    void batchChangeKeepsDistinctSsoGroupsAndDoesNotRevokeOtherScopeNewApproval() {
        enable("moved", A);
        grantInScope("moved", A, "AUTH_VIEWER");
        var changeA = submit("moved", RequestKind.CHANGE, "AUTH_CONTENT_MANAGER");
        move("moved", B);
        grantInScope("moved", B, "AUTH_VIEWER");
        var changeB = submit("moved", RequestKind.CHANGE, "AUTH_SYSTEM_ADMIN");
        requests.review("admin", List.of(changeA.id(), changeB.id()), Decision.APPROVE, "소속별 변경");
        assertThat(latest("moved", A, "AUTH_VIEWER").status()).isEqualTo(AssignmentStatus.REVOKED);
        assertThat(latest("moved", B, "AUTH_VIEWER").status()).isEqualTo(AssignmentStatus.REVOKED);
        assertThat(latest("moved", A, "AUTH_CONTENT_MANAGER").status()).isEqualTo(AssignmentStatus.APPROVED);
        assertThat(latest("moved", B, "AUTH_SYSTEM_ADMIN").status()).isEqualTo(AssignmentStatus.APPROVED);
        assertThat(effective.findEffectiveAuthorityIds("moved")).containsExactly("AUTH_SYSTEM_ADMIN");
        move("moved", A);
        assertThat(effective.findEffectiveAuthorityIds("moved")).containsExactly("AUTH_CONTENT_MANAGER");
    }

    @Test
    void scopeStalePendingConflictsAndDisablingModeDoesNotDowngradeStoredV3Request() {
        enable("revoked", A);
        var stale = submit("revoked", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        grantInScope("revoked", A, "AUTH_CONTENT_MANAGER");
        Object beforeRequests = requests.snapshotState();
        Object beforeCatalog = catalog.snapshotState();
        assertThatThrownBy(() -> requests.review("admin", List.of(stale.id()), Decision.REJECT, "반려"))
                .isInstanceOf(ApiException.class);
        assertThat(requests.snapshotState()).isEqualTo(beforeRequests);
        assertThat(catalog.snapshotState()).isEqualTo(beforeCatalog);
        var pending = submit("revoked", RequestKind.GRANT, "AUTH_VIEWER");
        scopes.save(new SsoAuthorityScopeService.WriteMode("revoked", false, 1L));
        var processed = requests.review("admin", List.of(pending.id()), Decision.APPROVE, "기존 v3 처리").get(0);
        assertThat(processed.workflowVersion()).isEqualTo(3);
        assertThat(processed.ssoOfficeCode()).isEqualTo(A.ssoOfficeCode());
        assertThat(latest("revoked", A, "AUTH_VIEWER").status()).isEqualTo(AssignmentStatus.APPROVED);
    }

    @Test
    void existingV2PendingBlocksNewV3InSameLocalOrganizationAndSystem() {
        var legacy = submit("viewer", RequestKind.GRANT, "AUTH_CONTENT_MANAGER");
        assertThat(legacy.workflowVersion()).isEqualTo(2);
        enable("viewer", A);
        assertThatThrownBy(() -> submit("viewer", RequestKind.GRANT, "AUTH_SYSTEM_ADMIN")).isInstanceOf(ApiException.class);
        var reviewed = requests.review("admin", List.of(legacy.id()), Decision.REJECT, "이전 요청 종료").get(0);
        assertThat(reviewed.workflowVersion()).isEqualTo(2);
        assertThat(submit("viewer", RequestKind.GRANT, "AUTH_SYSTEM_ADMIN").workflowVersion()).isEqualTo(3);
    }

    private void enable(String username, Scope scope) {
        for (Scope value : List.of(A, B)) {
            if (mappings.list().stream().noneMatch(m -> m.ssoOfficeCode().equals(value.ssoOfficeCode()))) {
                mappings.save(new OrganizationMappingService.WriteMapping(value.ssoOfficeCode(), value.ssoDepartmentCode(),
                        value.ssoOfficeCode(), value.ssoDepartmentCode(), "HQ", "", true, 0L));
            }
        }
        move(username, scope);
        scopes.save(new SsoAuthorityScopeService.WriteMode(username, true, 0L));
    }

    private void move(String username, Scope scope) {
        long version = affiliations.list().stream().filter(a -> a.username().equals(username))
                .mapToLong(UserSsoOrganizationService.Affiliation::version).findFirst().orElse(0L);
        affiliations.save(new UserSsoOrganizationService.WriteAffiliation(username, scope.ssoOfficeCode(), scope.ssoDepartmentCode(), version));
    }

    private AuthorityRequestService.AuthorityRequest submit(String username, RequestKind kind, String authorityId) {
        return requests.submit(username, kind, Set.of(authorityId), null, null, "SSO 업무 신청");
    }

    private void grantInScope(String username, Scope scope, String authorityId) {
        var assignment = catalog.createScopedAssignment(username, catalog.findUser(username).orElseThrow().organizationId(), authorityId,
                AssignmentStatus.APPROVED, LocalDate.now(clock), LocalDate.of(9999, 12, 31));
        scopes.record(assignment, scope, Instant.now(clock));
    }

    private AuthorizationCatalog.AuthorityAssignment latest(String username, Scope scope, String authorityId) {
        return scopes.latest(username, scope, "INFO").stream().filter(a -> a.authorityId().equals(authorityId)).findFirst().orElseThrow();
    }
}
