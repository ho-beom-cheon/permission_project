package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.example.permissiondemo.authorization.AuthorityTermsService.AgreementDecision;
import com.example.permissiondemo.authorization.AuthorityTermsService.ImportRequest;
import com.example.permissiondemo.authorization.AuthorityTermsService.TermRow;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorityTermsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:00Z");

    private AuthorizationCatalog catalog;
    private AuthorityTermsService service;

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        service = new AuthorityTermsService(catalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void bulkImportUpsertsAfterFullValidationAndVersionsOnce() {
        var result = service.importTerms(new ImportRequest(List.of(
                row(2, "AI", "01", "1.0", "선택", "본문", "02", null, null, "N", 20, "Y"),
                row(1, "AI", "01", "1.0", "필수", "본문", "02", null, null, "Y", 10, "Y"))));

        assertThat(result.created()).containsExactly(2L, 1L);
        assertThat(result.version()).isEqualTo(1);
        assertThat(service.terms()).extracting(AuthorityTermsService.AuthorityTerm::seq).containsExactly(1L, 2L);

        var same = service.importTerms(new ImportRequest(List.of(
                row(2, "AI", "01", "1.0", "선택", "본문", "02", null, null, "N", 20, "Y"),
                row(1, "AI", "01", "1.0", "필수", "본문", "02", null, null, "Y", 10, "Y"))));
        assertThat(same.unchanged()).containsExactly(2L, 1L);
        assertThat(same.version()).isEqualTo(1);

        var changed = service.importTerms(new ImportRequest(List.of(
                row(1, "AI", "01", "2.0", "필수 개정", "새 본문", "02", null, null, "Y", 10, "Y"),
                row(3, "CY", "01", "1.0", "신규", "본문", "02", null, null, "Y", 0, "Y"))));
        assertThat(changed.updated()).containsExactly(1L);
        assertThat(changed.created()).containsExactly(3L);
        assertThat(changed.version()).isEqualTo(2);
        assertThat(service.terms()).extracting(AuthorityTermsService.AuthorityTerm::seq).contains(2L);
    }

    @Test
    void planUsesOriginalSystemAndTermOrderAndIgnoresEnforcementPeriod() {
        authority("AB0000000001", "AB", true);
        authority("AI0000000001", "AI", true);
        authority("CY0000000001", "CY", true);
        service.importTerms(new ImportRequest(List.of(
                row(10, "AI", "01", "1", "선택", "", "02", LocalDate.of(2099, 1, 1), null, "N", 1, "Y"),
                row(11, "AI", "01", "1", "필수 구버전", "", "02", null, LocalDate.of(2020, 1, 1), "Y", 1, "Y"),
                row(12, "AI", "01", "2", "필수 최신", "", "02", null, null, "Y", 1, "Y"),
                row(13, "AI", "02", "1", "다른 유형", "", "02", null, null, "Y", 0, "Y"),
                row(14, "AI", "01", "1", "다른 상태", "", "01", null, null, "Y", 0, "Y"),
                row(15, "AI", "01", "1", "미사용", "", "02", null, null, "Y", 0, "N"),
                row(20, "AB", "01", "1", "AB 선택만", "", "02", null, null, "N", 0, "Y"))));

        var plan = service.plan("user", Set.of("AB0000000001", "CY0000000001", "AI0000000001"));

        assertThat(plan.systemIds()).containsExactly("AI", "CY", "AB");
        assertThat(plan.terms()).extracting(AuthorityTermsService.AuthorityTerm::seq)
                .containsExactly(12L, 11L, 10L);
        assertThat(plan.unconfiguredSystems()).containsExactly("CY", "AB");
    }

    @Test
    void decisionsMustMatchCurrentPlanAndPastPositiveAgreementCanBeReused() {
        authority("AI0000000002", "AI", true);
        service.importTerms(new ImportRequest(List.of(
                row(30, "AI", "01", "1", "첫 필수", "", "02", null, null, "Y", 0, "Y"))));
        service.recordDecisions("user", Set.of("AI0000000002"), List.of(new AgreementDecision(30, true)));

        service.importTerms(new ImportRequest(List.of(
                row(31, "AI", "01", "1", "새 필수", "", "02", null, null, "Y", 1, "Y"),
                row(32, "AI", "01", "1", "선택", "", "02", null, null, "N", 2, "Y"))));
        assertThatThrownBy(() -> service.validateDecisions("user", Set.of("AI0000000002"),
                List.of(new AgreementDecision(31, true))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.validateDecisions("user", Set.of("AI0000000002"), List.of(
                new AgreementDecision(30, false), new AgreementDecision(31, false),
                new AgreementDecision(32, true))))
                .isInstanceOf(IllegalArgumentException.class);

        var recorded = service.recordDecisions("user", Set.of("AI0000000002"), List.of(
                new AgreementDecision(30, false), new AgreementDecision(31, true),
                new AgreementDecision(32, false)));
        assertThat(recorded).hasSize(3).allMatch(item -> item.agreedAt().equals(NOW));
        assertThat(service.plan("user", Set.of("AI0000000002")).terms()).isEmpty();
        assertThat(service.agreements("user")).hasSize(4);

        service.importTerms(new ImportRequest(List.of(
                row(30, "AI", "01", "2", "첫 필수 개정", "본문 변경", "02", null, null, "Y", 0, "Y"))));
        assertThat(service.agreements("user")).hasSize(4);
    }

    @Test
    void importRejectsEveryInvalidBatchBeforeMutation() {
        assertInvalid(List.of(row(0, "AI", "01", "1", "제목", "", "02", null, null, "Y", 0, "Y")));
        assertInvalid(List.of(row(1, "ZZ", "01", "1", "제목", "", "02", null, null, "Y", 0, "Y")));
        assertInvalid(List.of(row(1, "AI", "1", "1", "제목", "", "02", null, null, "Y", 0, "Y")));
        assertInvalid(List.of(row(1, "AI", "01", "1", "제목", "", "02", null, null, "X", 0, "Y")));
        assertInvalid(List.of(row(1, "AI", "01", "1", "제목", "", "02",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), "Y", 0, "Y")));
        assertInvalid(List.of(row(1, "AI", "01", "1", "제목", "", "02", null, null, "Y", 10000, "Y")));
        assertInvalid(List.of(
                row(1, "AI", "01", "1", "정상 선행", "", "02", null, null, "Y", 0, "Y"),
                row(1, "AI", "01", "1", "중복 후행", "", "02", null, null, "Y", 0, "Y")));
        assertThat(service.terms()).isEmpty();
        assertThat(service.version()).isZero();
    }

    @Test
    void importAcceptsOriginalAndIsoDatesAndRejectsInvalidDateOrNullSortBeforeMutation() {
        service.importTerms(new ImportRequest(List.of(
                new TermRow(50, "AI", "01", "1", "원본 날짜", "", "02",
                        "20260904", "2026-09-30", "Y", 0, "Y"))));

        assertThat(service.terms().get(0).enforcementStart()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(service.terms().get(0).enforcementEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        Object before = service.snapshotState();

        assertThatThrownBy(() -> service.importTerms(new ImportRequest(List.of(
                new TermRow(51, "AI", "01", "1", "정상 선행", "", "02",
                        "20260905", null, "Y", 1, "Y"),
                new TermRow(52, "AI", "01", "1", "잘못된 날짜", "", "02",
                        "20260230", null, "Y", 2, "Y")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);

        assertThatThrownBy(() -> service.importTerms(new ImportRequest(List.of(
                new TermRow(53, "AI", "01", "1", "정렬 누락", "", "02",
                        null, null, "Y", null, "Y")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);
    }

    @Test
    void planRejectsMissingOrInactiveAuthoritiesAndIgnoresNonSourceSystems() {
        authority("AI0000000003", "AI", false);
        authority("GENERIC_AUTH", "INFO", true);

        assertThatThrownBy(() -> service.plan("user", Set.of("MISSING"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.plan("user", Set.of("AI0000000003"))).isInstanceOf(ApiException.class);
        var generic = service.plan("user", Set.of("GENERIC_AUTH"));
        assertThat(generic.systemIds()).isEmpty();
        assertThat(generic.unconfiguredSystems()).isEmpty();
    }

    @Test
    void storedStateRestoresTermsAgreementsAndVersion() {
        authority("AI0000000004", "AI", true);
        service.importTerms(new ImportRequest(List.of(
                row(40, "AI", "01", "1", "복원", "본문", "02", null, null, "Y", 0, "Y"))));
        service.recordDecisions("user", Set.of("AI0000000004"), List.of(new AgreementDecision(40, true)));
        Object state = service.snapshotState();

        AuthorityTermsService restored = new AuthorityTermsService(catalog, Clock.fixed(NOW, ZoneOffset.UTC));
        restored.restoreState(state);

        assertThat(restored.terms()).isEqualTo(service.terms());
        assertThat(restored.agreements("user")).isEqualTo(service.agreements("user"));
        assertThat(restored.version()).isEqualTo(1);
    }

    @Test
    void agreementImportPreservesExactDuplicatesAndIsIdempotent() {
        authority("AI0000000005", "AI", true);
        service.importTerms(new ImportRequest(List.of(
                row(60, "AI", "01", "1", "이력", "본문", "02", null, null, "Y", 0, "Y"))));
        var first = service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 60, "2026-09-04T14:30:00", "Y", "10.0.0.1",
                        "2026-09-04T14:30:01", "user", 1),
                agreement("user", 60, "2026-09-04T14:30:00", "Y", "10.0.0.1",
                        "2026-09-04T14:30:01", "user", 2))));

        assertThat(first.created()).isEqualTo(2);
        assertThat(first.unchanged()).isZero();
        assertThat(first.version()).isEqualTo(2);
        assertThat(service.agreements("user")).hasSize(2)
                .extracting(AuthorityTermsService.Agreement::duplicateOrdinal).containsExactly(1, 2);
        assertThat(service.agreements("user").get(0).agreedAt())
                .isEqualTo(Instant.parse("2026-09-04T05:30:00Z"));

        var replay = service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 60, "2026-09-04T14:30:00", "Y", "10.0.0.1",
                        "2026-09-04T14:30:01", "user", 1),
                agreement("user", 60, "2026-09-04T14:30:00", "Y", "10.0.0.1",
                        "2026-09-04T14:30:01", "user", 2))));

        assertThat(replay.created()).isZero();
        assertThat(replay.unchanged()).isEqualTo(2);
        assertThat(replay.version()).isEqualTo(2);
        assertThat(service.agreements("user")).hasSize(2);
    }

    @Test
    void importedPositiveAgreementSatisfiesPlanWhileNegativeDoesNot() {
        authority("AI0000000006", "AI", true);
        service.importTerms(new ImportRequest(List.of(
                row(61, "AI", "01", "1", "필수", "본문", "02", null, null, "Y", 0, "Y"))));
        service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("negative", 61, "2026-09-04T14:00:00", "N", null, null, null, 1),
                agreement("positive", 61, "2026-09-04T14:01:00", "Y", null, null, null, 1))));

        assertThat(service.plan("negative", Set.of("AI0000000006")).terms()).hasSize(1);
        assertThat(service.plan("positive", Set.of("AI0000000006")).terms()).isEmpty();
    }

    @Test
    void agreementImportRejectsInvalidBatchBeforeMutation() {
        service.importTerms(new ImportRequest(List.of(
                row(62, "AI", "01", "1", "검증", "본문", "02", null, null, "Y", 0, "Y"))));
        Object before = service.snapshotState();

        assertThatThrownBy(() -> service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 62, "2026-09-04T14:00:00", "Y", null, null, null, 1),
                agreement("user", 999, "2026-09-04T14:00:00", "Y", null, null, null, 1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);

        assertThatThrownBy(() -> service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 62, "2026-09-04 14:00:00", "Y", null, null, null, 1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);

        assertThatThrownBy(() -> service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 62, "2026-09-04T14:00:00", "Y", null, null, null, 1),
                agreement("user", 62, "2026-02-30T14:00:00", "Y", null, null, null, 2)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);

        var duplicate = agreement("user", 62, "2026-09-04T14:00:00", "Y", null, null, null, 1);
        assertThatThrownBy(() -> service.importAgreements(
                new AuthorityTermsService.AgreementImportRequest(List.of(duplicate, duplicate))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);

        assertThatThrownBy(() -> service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 62, "2026-09-04T14:00:00", "Y", null, null, null, 2)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);

        assertThatThrownBy(() -> service.importAgreements(new AuthorityTermsService.AgreementImportRequest(List.of(
                agreement("user", 62, "2026-09-04T14:00:00", "Y", null, null, null, 1),
                agreement("user", 62, "2026-09-04T14:00:00", "Y", null, null, null, 3)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.snapshotState()).isEqualTo(before);
    }

    @Test
    void legacyFourFieldAgreementJsonRemainsReadable() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();

        var agreement = mapper.readValue("""
                {"username":"legacy","seq":63,"agreed":true,"agreedAt":"2026-09-04T05:00:00Z"}
                """, AuthorityTermsService.Agreement.class);

        assertThat(agreement).isEqualTo(new AuthorityTermsService.Agreement(
                "legacy", 63, true, Instant.parse("2026-09-04T05:00:00Z")));
        assertThat(agreement.duplicateOrdinal()).isZero();
    }

    private void assertInvalid(List<TermRow> rows) {
        assertThatThrownBy(() -> service.importTerms(new ImportRequest(rows)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void authority(String id, String systemId, boolean active) {
        catalog.saveAuthority(new AuthorityDefinition(id, id, active, systemId, null, ""));
    }

    private TermRow row(long seq, String systemId, String typeCode, String version, String title, String body,
            String statusCode, LocalDate start, LocalDate end, String required, int sortOrder, String active) {
        return new TermRow(seq, systemId, typeCode, version, title, body, statusCode,
                start == null ? null : start.toString(), end == null ? null : end.toString(),
                required, sortOrder, active);
    }

    private AuthorityTermsService.AgreementRow agreement(String username, long termSequence, String agreedAt,
            String agreedFlag, String connectionIp, String registeredAt, String registrarId, int duplicateOrdinal) {
        return new AuthorityTermsService.AgreementRow(username, termSequence, agreedAt, agreedFlag,
                connectionIp, registeredAt, registrarId, duplicateOrdinal);
    }
}
