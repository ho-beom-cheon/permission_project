package com.example.permissiondemo.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AdminService;
import com.example.permissiondemo.authorization.AuthorityHistoryService;
import com.example.permissiondemo.authorization.AuthorityHistoryQueryService;
import com.example.permissiondemo.authorization.AuthoritySsoHistoryLinkService;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService;
import com.example.permissiondemo.authorization.EffectiveAuthorityService;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 새 저장 영역이 없는 이전 형식과 신규 조직·처리 이력의 파일 DB 복원/원자성을 확인한다. */
class MigrationStatePersistenceTest {
    private ConfigurableApplicationContext open(String directory) {
        var context = new SpringApplicationBuilder(PermissionDemoApplication.class, ProbeConfiguration.class)
                .run("--spring.profiles.active=demo", "--server.port=0", "--server.address=127.0.0.1",
                        "--app.storage.memory=false", "--app.storage.directory=" + directory,
                        "--logging.level.root=ERROR", "--spring.main.banner-mode=off");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "unused", List.of()));
        return context;
    }

    private void close(ConfigurableApplicationContext context) {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.getBean(JdbcTemplate.class).execute("SHUTDOWN");
        context.close();
    }

    @Test
    void oldStoreOpensWithoutInventedHistoryAndNewRecordsSurviveReopen() {
        String directory = "target/migration-state-tests/" + UUID.randomUUID();
        Object oldRequests;
        Object oldCatalog;
        var old = open(directory);
        try {
            var requests = old.getBean(AuthorityRequestService.class);
            var request = requests.submit("manager", AuthorityRequestService.RequestKind.GRANT,
                    Set.of("AUTH_VIEWER"), LocalDate.now(), LocalDate.now().plusDays(10), "이전 버전 합성 신청");
            requests.review("admin", List.of(request.id()), AuthorityRequestService.Decision.REJECT, "이전 버전 합성 반려");
            oldRequests = requests.snapshotState();
            oldCatalog = old.getBean(AuthorizationCatalog.class).snapshotState();
            // 이 테스트 전용 DB에서만 새 state key를 제거해 이전 버전 저장 형식을 재현한다.
            old.getBean(JdbcTemplate.class).update(
                    "DELETE FROM APP_STATE WHERE STATE_KEY IN ('organization-mappings', 'authority-history', 'user-sso-organizations', 'authority-sso-history-links', 'sso-authorizations')");
        } finally { close(old); }

        Object savedMappings;
        Object savedHistory;
        Object savedAffiliations;
        Object savedLinks;
        Object savedFullHistory;
        var upgraded = open(directory);
        try {
            var mappings = upgraded.getBean(OrganizationMappingService.class);
            var history = upgraded.getBean(AuthorityHistoryService.class);
            var requests = upgraded.getBean(AuthorityRequestService.class);
            var affiliations = upgraded.getBean(UserSsoOrganizationService.class);
            assertThat(mappings.list()).isEmpty();
            assertThat(history.all()).isEmpty();
            assertThat(affiliations.list()).isEmpty();
            assertThat(upgraded.getBean(AuthoritySsoHistoryLinkService.class).list()).isEmpty();
            assertThat(upgraded.getBean(SsoAuthorityScopeService.class).isEnabled("viewer")).isFalse();
            assertThat(requests.snapshotState()).isEqualTo(oldRequests);
            assertThat(upgraded.getBean(AuthorizationCatalog.class).snapshotState()).isEqualTo(oldCatalog);

            upgraded.getBean(DirectoryService.class).saveDepartment(
                    new DirectoryService.Department("PERSIST", "HQ", "합성 부서", "", true));
            Object beforeMapping = upgraded.getBean(AuthorizationCatalog.class).snapshotState();
            mappings.save(new OrganizationMappingService.WriteMapping(
                    "SSO_TEST", "SSO_DEP", "합성 SSO 사업소", "합성 SSO 부서", "HQ", "PERSIST", true, 0));
            affiliations.save(new UserSsoOrganizationService.WriteAffiliation("viewer", "SSO_TEST", "SSO_DEP", 0));
            assertThat(upgraded.getBean(AuthorizationCatalog.class).snapshotState()).isEqualTo(beforeMapping);
            var request = requests.submit("viewer", AuthorityRequestService.RequestKind.GRANT,
                    Set.of("AUTH_CONTENT_MANAGER"), LocalDate.now(), LocalDate.now().plusDays(10), "새 버전 합성 신청");
            requests.review("admin", List.of(request.id()), AuthorityRequestService.Decision.APPROVE, "확인");
            upgraded.getBean(AdminService.class).revokeAuthority(
                    "viewer", "AUTH_CONTENT_MANAGER", AuthorizationCatalog.AssignmentType.DIRECT, "회수 사유");
            assertThat(history.list("viewer", null, PageQuery.of(0, 100)).totalElements()).isEqualTo(3);
            requests.submit("moved", AuthorityRequestService.RequestKind.GRANT,
                    Set.of("AUTH_VIEWER"), null, null, "재시작 전 대기 신청");
            savedMappings = mappings.snapshotState();
            savedHistory = history.snapshotState();
            savedAffiliations = affiliations.snapshotState();
            var links = upgraded.getBean(AuthoritySsoHistoryLinkService.class);
            assertThat(links.list()).hasSize(3);
            savedLinks = links.snapshotState();
            savedFullHistory = upgraded.getBean(AuthorityHistoryQueryService.class).list(null, null, PageQuery.of(0, 100));
        } finally { close(upgraded); }

        var reopened = open(directory);
        try {
            assertThat(reopened.getBean(OrganizationMappingService.class).snapshotState()).isEqualTo(savedMappings);
            assertThat(reopened.getBean(UserSsoOrganizationService.class).snapshotState()).isEqualTo(savedAffiliations);
            assertThat(reopened.getBean(AuthoritySsoHistoryLinkService.class).snapshotState()).isEqualTo(savedLinks);
            assertThat(reopened.getBean(AuthorityHistoryQueryService.class).list(null, null, PageQuery.of(0, 100))).isEqualTo(savedFullHistory);
            var history = reopened.getBean(AuthorityHistoryService.class);
            assertThat(history.snapshotState()).isEqualTo(savedHistory);
            var requests = reopened.getBean(AuthorityRequestService.class);
            var pending = requests.list("moved", AuthorityRequestService.RequestStatus.PENDING, PageQuery.of(0, 20)).content().get(0);
            assertThat(pending.workflowVersion()).isEqualTo(2);
            assertThat(requests.review("admin", List.of(pending.id()), AuthorityRequestService.Decision.APPROVE, "복원 후 승인")
                    .get(0).status()).isEqualTo(AuthorityRequestService.RequestStatus.APPROVED);
            long latest = history.all().stream().mapToLong(AuthorityHistoryService.HistoryEvent::eventId).max().orElseThrow();
            reopened.getBean(AuthorityRequestService.class).submit("viewer", AuthorityRequestService.RequestKind.GRANT,
                    Set.of("AUTH_CONTENT_MANAGER"), LocalDate.now(), LocalDate.now().plusDays(10), "재시작 후 신청");
            assertThat(history.all().stream().mapToLong(AuthorityHistoryService.HistoryEvent::eventId).max().orElseThrow()).isGreaterThan(latest);
        } finally { close(reopened); }
    }

    @Test
    void failureRollsBackMappingRequestHistoryAndStoredRevisionTogether() {
        var context = open("target/migration-state-tests/" + UUID.randomUUID());
        try {
            var repository = context.getBean(ComponentStateRepository.class);
            Map<String, String> before = repository.load();
            long revision = repository.revision();
            Object mappings = context.getBean(OrganizationMappingService.class).snapshotState();
            Object history = context.getBean(AuthorityHistoryService.class).snapshotState();
            Object affiliations = context.getBean(UserSsoOrganizationService.class).snapshotState();
            Object links = context.getBean(AuthoritySsoHistoryLinkService.class).snapshotState();
            Object modes = context.getBean(SsoAuthorityScopeService.class).snapshotState();
            Object requests = context.getBean(AuthorityRequestService.class).snapshotState();
            assertThatThrownBy(() -> context.getBean(FailureProbe.class).fail())
                    .isInstanceOf(IllegalStateException.class).hasMessage("synthetic migration rollback");
            assertThat(repository.load()).isEqualTo(before);
            assertThat(repository.revision()).isEqualTo(revision);
            assertThat(context.getBean(OrganizationMappingService.class).snapshotState()).isEqualTo(mappings);
            assertThat(context.getBean(AuthorityHistoryService.class).snapshotState()).isEqualTo(history);
            assertThat(context.getBean(UserSsoOrganizationService.class).snapshotState()).isEqualTo(affiliations);
            assertThat(context.getBean(AuthoritySsoHistoryLinkService.class).snapshotState()).isEqualTo(links);
            assertThat(context.getBean(SsoAuthorityScopeService.class).snapshotState()).isEqualTo(modes);
            assertThat(context.getBean(AuthorityRequestService.class).snapshotState()).isEqualTo(requests);
        } finally { close(context); }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean FailureProbe migrationFailureProbe(OrganizationMappingService mappings, AuthorityRequestService requests,
                UserSsoOrganizationService affiliations, SsoAuthorityScopeService scopes) {
            return new FailureProbe(mappings, requests, affiliations, scopes);
        }
    }

    @StateBoundary
    public static class FailureProbe {
        private final OrganizationMappingService mappings;
        private final AuthorityRequestService requests;
        private final UserSsoOrganizationService affiliations;
        private final SsoAuthorityScopeService scopes;
        FailureProbe(OrganizationMappingService mappings, AuthorityRequestService requests, UserSsoOrganizationService affiliations, SsoAuthorityScopeService scopes) {
            this.mappings = mappings; this.requests = requests; this.affiliations = affiliations; this.scopes = scopes;
        }
        public void fail() {
            mappings.save(new OrganizationMappingService.WriteMapping(
                    "SSO_ROLLBACK", "SSO_DEP", "합성", "합성", "", "", true, 0));
            affiliations.save(new UserSsoOrganizationService.WriteAffiliation("viewer", "SSO_ROLLBACK", "SSO_DEP", 0));
            scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", true, 0));
            requests.submit("viewer", AuthorityRequestService.RequestKind.GRANT,
                    Set.of("AUTH_CONTENT_MANAGER"), LocalDate.now(), LocalDate.now().plusDays(10), "실패 검증");
            throw new IllegalStateException("synthetic migration rollback");
        }
    }

    @Test
    void enabledSsoModeAndSeparatePendingRequestsSurviveReopen() {
        String directory = "target/migration-state-tests/" + UUID.randomUUID();
        long requestA;
        long requestB;
        var initial = open(directory);
        try {
            var mappings = initial.getBean(OrganizationMappingService.class);
            for (String office : List.of("SSO_A", "SSO_B")) mappings.save(new OrganizationMappingService.WriteMapping(
                    office, "DEP", "합성 사업소", "합성 부서", "HQ", "", true, 0));
            var affiliations = initial.getBean(UserSsoOrganizationService.class);
            affiliations.save(new UserSsoOrganizationService.WriteAffiliation("viewer", "SSO_A", "DEP", 0));
            initial.getBean(SsoAuthorityScopeService.class).save(new SsoAuthorityScopeService.WriteMode("viewer", true, 0));
            var requests = initial.getBean(AuthorityRequestService.class);
            var first = requests.submit("viewer", AuthorityRequestService.RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"), null, null, "A 신청");
            assertThat(first.workflowVersion()).isEqualTo(3);
            requestA = first.id();
            affiliations.save(new UserSsoOrganizationService.WriteAffiliation("viewer", "SSO_B", "DEP", 1));
            requestB = requests.submit("viewer", AuthorityRequestService.RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"), null, null, "B 신청").id();
        } finally { close(initial); }
        var reopened = open(directory);
        try {
            var scopes = reopened.getBean(SsoAuthorityScopeService.class);
            assertThat(scopes.isEnabled("viewer")).isTrue();
            assertThat(scopes.requireCurrentScope("viewer").ssoOfficeCode()).isEqualTo("SSO_B");
            var requests = reopened.getBean(AuthorityRequestService.class);
            var effective = reopened.getBean(EffectiveAuthorityService.class);
            requests.review("admin", List.of(requestA), AuthorityRequestService.Decision.APPROVE, "A 원래 소속 승인");
            assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
            requests.review("admin", List.of(requestB), AuthorityRequestService.Decision.APPROVE, "B 승인");
            assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly("AUTH_CONTENT_MANAGER");
        } finally { close(reopened); }
    }
}
