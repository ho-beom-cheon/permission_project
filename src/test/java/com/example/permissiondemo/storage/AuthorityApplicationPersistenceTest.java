package com.example.permissiondemo.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityHistoryService;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.AuthorityTermsService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.web.AuthorityWorkflowController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 권한 신청 한 경계에서 저장한 약관·신청자·담당지역·신청 이력이 재시작 후에도 함께 복원되는지 확인한다. */
class AuthorityApplicationPersistenceTest {

    @Test
    void termsApplicantRegionsAndRequestSurviveReopen() {
        String storage = "target/authority-application-state/" + UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String authorityId = "AI_REOPEN_" + suffix;
        String regionId = "REG_REOPEN_" + suffix;
        long termSequence = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
        Object expectedTerms;
        Object expectedDirectory;
        Object expectedRequests;
        Object expectedHistory;
        long requestId;

        var initial = open(storage);
        try {
            var catalog = initial.getBean(AuthorizationCatalog.class);
            var directory = initial.getBean(DirectoryService.class);
            var terms = initial.getBean(AuthorityTermsService.class);
            catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                    authorityId, "재시작 합성 권한", true, "AI", null, ""));
            directory.saveRegion(new DirectoryService.Region(
                    regionId, "HQ", "D1", "합성 구", "재시작 합성 지역", true));
            terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(
                    new AuthorityTermsService.TermRow(termSequence, "AI", "01", "v1",
                            "재시작 합성 약관", "재시작 합성 본문", "02", null, null, "Y", 1, "Y"))));

            var request = new AuthorityWorkflowController.SubmitRequest(
                    AuthorityRequestService.RequestKind.GRANT, Set.of(authorityId), null, null,
                    "재시작 복원 검증", List.of(new AuthorityWorkflowController.TermDecisionRequest(termSequence, true)),
                    new AuthorityWorkflowController.ApplicantRequest(
                            "R1", "0212345678", "01012345678", "Admin@Example.COM", "재시작 검증 업무", Set.of(regionId)));
            requestId = initial.getBean(AuthorityWorkflowController.class).submitBatch(request).data().get(0).id();
            expectedTerms = terms.snapshotState();
            expectedDirectory = directory.snapshotState();
            expectedRequests = initial.getBean(AuthorityRequestService.class).snapshotState();
            expectedHistory = initial.getBean(AuthorityHistoryService.class).snapshotState();
        } finally {
            close(initial);
        }

        var reopened = open(storage);
        try {
            assertThat(reopened.getBean(AuthorityTermsService.class).snapshotState()).isEqualTo(expectedTerms);
            assertThat(reopened.getBean(DirectoryService.class).snapshotState()).isEqualTo(expectedDirectory);
            assertThat(reopened.getBean(AuthorityRequestService.class).snapshotState()).isEqualTo(expectedRequests);
            assertThat(reopened.getBean(AuthorityHistoryService.class).snapshotState()).isEqualTo(expectedHistory);
            assertThat(reopened.getBean(DirectoryService.class).person("admin").regionIds()).containsExactly(regionId);
            assertThat(reopened.getBean(AuthorityTermsService.class).agreements("admin")).hasSize(1);
            assertThat(reopened.getBean(AuthorityRequestService.class)
                    .list("admin", AuthorityRequestService.RequestStatus.PENDING, PageQuery.of(0, 20)).totalElements())
                    .isEqualTo(1);
            assertThat(reopened.getBean(AuthorityRequestService.class)
                    .review("admin", List.of(requestId), AuthorityRequestService.Decision.APPROVE, "복원 후 승인")
                    .get(0).status()).isEqualTo(AuthorityRequestService.RequestStatus.APPROVED);
        } finally {
            close(reopened);
        }
    }

    @Test
    void importedAgreementSourceRowsAndMetadataSurviveReopen() {
        String storage = "target/authority-agreement-state/" + UUID.randomUUID();
        long termSequence = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;
        Object expectedTerms;

        var initial = open(storage);
        try {
            var terms = initial.getBean(AuthorityTermsService.class);
            terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(
                    new AuthorityTermsService.TermRow(termSequence, "AI", "01", "v1",
                            "이력 재시작 합성 약관", "이력 재시작 합성 본문", "02",
                            null, null, "Y", 1, "Y"))));
            terms.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                    new AuthorityTermsService.AgreementRow("legacy-user", termSequence,
                            "2026-09-04T14:30:00", "Y", "10.0.0.1",
                            "2026-09-04T14:30:01", "legacy-registrar", 1),
                    new AuthorityTermsService.AgreementRow("legacy-user", termSequence,
                            "2026-09-04T14:30:00", "Y", "10.0.0.1",
                            "2026-09-04T14:30:01", "legacy-registrar", 2))));
            expectedTerms = terms.snapshotState();
        } finally {
            close(initial);
        }

        var reopened = open(storage);
        try {
            var restored = reopened.getBean(AuthorityTermsService.class);
            assertThat(restored.snapshotState()).isEqualTo(expectedTerms);
            assertThat(restored.agreements("legacy-user")).hasSize(2)
                    .extracting(AuthorityTermsService.Agreement::duplicateOrdinal).containsExactly(1, 2);
            assertThat(restored.agreements("legacy-user").get(0))
                    .extracting(AuthorityTermsService.Agreement::agreedAt,
                            AuthorityTermsService.Agreement::connectionIp,
                            AuthorityTermsService.Agreement::registeredAt,
                            AuthorityTermsService.Agreement::registrarId)
                    .containsExactly(Instant.parse("2026-09-04T05:30:00Z"), "10.0.0.1",
                            Instant.parse("2026-09-04T05:30:01Z"), "legacy-registrar");
        } finally {
            close(reopened);
        }
    }

    private ConfigurableApplicationContext open(String storage) {
        var context = new SpringApplicationBuilder(PermissionDemoApplication.class)
                .run("--spring.profiles.active=demo", "--server.port=0", "--server.address=127.0.0.1",
                        "--app.storage.memory=false", "--app.storage.directory=" + storage,
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
}
