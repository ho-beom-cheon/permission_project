package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityHistoryService;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.AuthorityTermsService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.directory.DirectoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthorityTermsIntegrationTest {

    private static final String IMPORT_URL = "/api/admin/authority-terms/import";
    private static final String AGREEMENT_IMPORT_URL = "/api/admin/authority-term-agreements/import";
    private static final String PLAN_URL = "/api/me/authority-requests/terms-plan";
    private static final String BATCH_URL = "/api/me/authority-requests/batch";
    private static final AtomicLong TERM_SEQUENCE = new AtomicLong(20000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;
    @Autowired AuthorityTermsService terms;
    @Autowired AuthorityRequestService requests;
    @Autowired AuthorityHistoryService history;
    @Autowired AuditEventService audit;
    @Autowired DirectoryService directory;

    String username;
    String ai;
    String cy;
    String ab;
    long seq;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        username = "terms_" + suffix.toLowerCase();
        ai = "AI_TERMS_" + suffix;
        cy = "CY_TERMS_" + suffix;
        ab = "AB_TERMS_" + suffix;
        seq = TERM_SEQUENCE.addAndGet(100);
        directory.savePerson(new DirectoryService.Person(
                username, username, "HQ", "", "", "", "", "", "", true, Set.of()));
    }

    @Test
    void adminImportRequiresAuthorityUpdateAndCsrf() throws Exception {
        var request = new AuthorityTermsService.ImportRequest(List.of(term(seq, "AI", true, 10)));
        Object before = terms.snapshotState();

        mvc.perform(post(IMPORT_URL).with(user("viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());
        assertThat(terms.snapshotState()).isEqualTo(before);

        mvc.perform(post(IMPORT_URL).with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());
        assertThat(terms.snapshotState()).isEqualTo(before);

        mvc.perform(post(IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created[0]").value(seq))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void adminImportAcceptsExportDateFormatsAndRejectsInvalidDateOrNullSortAtomically() throws Exception {
        long originalDateSeq = seq + 10;
        long isoDateSeq = seq + 11;
        mvc.perform(post(IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("terms", List.of(
                                rawTerm(originalDateSeq, "20260904", "20260930", 1),
                                rawTerm(isoDateSeq, "2026-10-01", "2026-10-31", 2))))))
                .andExpect(status().isOk());

        assertThat(terms.terms().stream().filter(item -> item.seq() == originalDateSeq).findFirst().orElseThrow()
                .enforcementStart()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(terms.terms().stream().filter(item -> item.seq() == isoDateSeq).findFirst().orElseThrow()
                .enforcementEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        Snapshot before = snapshot();

        mvc.perform(post(IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("terms", List.of(
                                rawTerm(seq + 12, "20260905", null, 3),
                                rawTerm(seq + 13, "20260230", null, 4))))))
                .andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);

        Map<String, Object> nullSort = rawTerm(seq + 14, null, null, 5);
        nullSort.put("sortOrder", null);
        mvc.perform(post(IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("terms", List.of(nullSort)))))
                .andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void agreementImportRequiresAuthorityUpdateAndCsrfAndReplaysWithoutDuplicates() throws Exception {
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(term(seq, "AI", true, 10))));
        Map<String, Object> payload = Map.of("agreements", List.of(agreement(seq, 1)));

        mvc.perform(post(AGREEMENT_IMPORT_URL).with(user("viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isForbidden());
        assertThat(terms.agreements("legacy-user")).isEmpty();

        mvc.perform(post(AGREEMENT_IMPORT_URL).with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isForbidden());
        assertThat(terms.agreements("legacy-user")).isEmpty();

        mvc.perform(post(AGREEMENT_IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.unchanged").value(0))
                .andExpect(jsonPath("$.data.version").value(2));

        mvc.perform(post(AGREEMENT_IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.unchanged").value(1))
                .andExpect(jsonPath("$.data.version").value(2));
        assertThat(terms.agreements("legacy-user")).hasSize(1);
    }

    @Test
    void invalidAgreementBatchRollsBackTermsAndAuditState() throws Exception {
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(term(seq, "AI", true, 10))));
        Snapshot before = snapshot();
        Map<String, Object> missingTerm = agreement(seq + 999, 1);

        mvc.perform(post(AGREEMENT_IMPORT_URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("agreements", List.of(agreement(seq, 1), missingTerm)))))
                .andExpect(status().isBadRequest());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void planUsesOriginalSystemOrderAndReportsSystemWithoutRequiredTerms() throws Exception {
        saveAuthority(ai, "AI", true);
        saveAuthority(cy, "CY", true);
        saveAuthority(ab, "AB", true);
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(
                term(seq, "AB", true, 10),
                term(seq + 1, "AI", false, 20),
                term(seq + 2, "CY", false, 10),
                term(seq + 3, "AI", true, 10))));

        mvc.perform(post(PLAN_URL).with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("authorityIds", Set.of(ab, cy, ai)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredSystems[0]").value("AI"))
                .andExpect(jsonPath("$.data.requiredSystems[1]").value("AB"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].termSequence").value(seq + 3))
                .andExpect(jsonPath("$.data.items[1].termSequence").value(seq + 1))
                .andExpect(jsonPath("$.data.items[2].termSequence").value(seq))
                .andExpect(jsonPath("$.data.unconfiguredSystems[0]").value("CY"));
    }

    @Test
    void missingExtraneousAndRejectedRequiredDecisionsLeaveEveryStateUnchanged() throws Exception {
        saveAuthority(ai, "AI", true);
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(term(seq, "AI", true, 10))));
        Snapshot before = snapshot();

        submit(Set.of(ai), List.of()).andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);

        submit(Set.of(ai), List.of(decision(seq, true), decision(seq + 999, true)))
                .andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);

        submit(Set.of(ai), List.of(decision(seq, false))).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void validDecisionsCreateOneRequestPerAuthorityAndPositiveHistoryIsReused() throws Exception {
        saveAuthority(ai, "AI", true);
        saveAuthority(ab, "AB", true);
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(
                term(seq, "AI", true, 10), term(seq + 1, "AB", true, 10))));

        submit(Set.of(ab, ai), List.of(decision(seq, true), decision(seq + 1, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].systemId").value("AI"))
                .andExpect(jsonPath("$.data[0].authorityIds[0]").value(ai))
                .andExpect(jsonPath("$.data[1].systemId").value("AB"))
                .andExpect(jsonPath("$.data[1].authorityIds[0]").value(ab));

        assertThat(terms.agreements(username)).hasSize(2)
                .allMatch(AuthorityTermsService.Agreement::agreed);
        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isEqualTo(2);
        assertThat(catalog.assignmentsFor(username)).hasSize(2)
                .allMatch(item -> item.status() == AuthorizationCatalog.AssignmentStatus.PENDING);
        assertThat(history.list(username, null, PageQuery.of(0, 100)).totalElements()).isEqualTo(2);

        mvc.perform(post(PLAN_URL).with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("authorityIds", Set.of(ab, ai)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredSystems.length()").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void inactiveLaterAuthorityAndPostAgreementConflictBothRollbackAllState() throws Exception {
        saveAuthority(ai, "AI", true);
        saveAuthority(ab, "AB", false);
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(
                term(seq, "AI", true, 10), term(seq + 1, "AB", true, 10))));
        Snapshot beforeInactive = snapshot();

        submit(Set.of(ai, ab), List.of(decision(seq, true), decision(seq + 1, true)))
                .andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(beforeInactive);

        saveAuthority(ab, "AB", true);
        catalog.saveAssignment(username, ab, AuthorizationCatalog.AssignmentType.DIRECT,
                LocalDate.of(2000, 1, 1), LocalDate.of(9999, 12, 31), null);
        Snapshot beforeLateConflict = snapshot();

        submit(Set.of(ai, ab), List.of(decision(seq, true), decision(seq + 1, true)))
                .andExpect(status().isConflict());

        assertThat(snapshot()).isEqualTo(beforeLateConflict);
        assertThat(terms.agreements(username)).isEmpty();
        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isZero();
        assertThat(history.list(username, null, PageQuery.of(0, 100)).totalElements()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            Set<String> authorityIds, List<Map<String, Object>> decisions) throws Exception {
        return mvc.perform(post(BATCH_URL).with(user(username)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of(
                        "kind", "GRANT",
                        "authorityIds", authorityIds,
                        "reason", "합성 약관 신청",
                        "termDecisions", decisions))));
    }

    private Map<String, Object> decision(long sequence, boolean agreed) {
        return Map.of("termSequence", sequence, "agreed", agreed);
    }

    private Map<String, Object> rawTerm(long sequence, String start, String end, int sortOrder) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", sequence);
        row.put("systemId", "AI");
        row.put("typeCode", "01");
        row.put("version", "v1");
        row.put("title", "HTTP 약관 " + sequence);
        row.put("body", "HTTP 약관 본문");
        row.put("statusCode", "02");
        row.put("enforcementStart", start);
        row.put("enforcementEnd", end);
        row.put("requiredFlag", "Y");
        row.put("sortOrder", sortOrder);
        row.put("useFlag", "Y");
        return row;
    }

    private Map<String, Object> agreement(long termSequence, int duplicateOrdinal) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("username", "legacy-user");
        row.put("termSequence", termSequence);
        row.put("agreedAt", "2026-09-04T14:30:00");
        row.put("agreedFlag", "Y");
        row.put("connectionIp", "10.0.0.1");
        row.put("registeredAt", "2026-09-04T14:30:01");
        row.put("registrarId", "legacy-user");
        row.put("duplicateOrdinal", duplicateOrdinal);
        return row;
    }

    private AuthorityTermsService.TermRow term(long sequence, String systemId, boolean required, int sortOrder) {
        return new AuthorityTermsService.TermRow(sequence, systemId, "01", "v1",
                systemId + " 합성 약관 " + sequence, "합성 약관 본문", "02",
                null, null, required ? "Y" : "N", sortOrder, "Y");
    }

    private void saveAuthority(String id, String systemId, boolean active) {
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(id, id, active, systemId, null, ""));
    }

    private Snapshot snapshot() {
        return new Snapshot(terms.snapshotState(), catalog.snapshotState(), requests.snapshotState(),
                history.snapshotState(), audit.snapshotState());
    }

    private record Snapshot(Object terms, Object catalog, Object requests, Object history, Object audit) { }
}
