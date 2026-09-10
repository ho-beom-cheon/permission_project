package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AuthorityApplicantIntegrationTest {

    private static final String APPLICANT_URL = "/api/me/authority-requests/applicant";
    private static final String BATCH_URL = "/api/me/authority-requests/batch";
    private static final AtomicLong TERM_SEQUENCE = new AtomicLong(50000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DirectoryService directory;
    @Autowired AuthorizationCatalog catalog;
    @Autowired AuthorityTermsService terms;
    @Autowired AuthorityRequestService requests;
    @Autowired AuthorityHistoryService history;
    @Autowired AuditEventService audit;

    String suffix;
    String username;
    String localRegion;
    String inactiveRegion;
    String foreignRegion;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        username = "applicant_" + suffix.toLowerCase();
        localRegion = "REG_LOCAL_" + suffix;
        inactiveRegion = "REG_OFF_" + suffix;
        foreignRegion = "REG_FOREIGN_" + suffix;
        String otherOffice = "OFFICE_" + suffix;
        directory.saveOffice(new DirectoryService.Office(otherOffice, "합성 타 사업소", "", "", "", true));
        directory.saveRegion(new DirectoryService.Region(localRegion, "HQ", "D1", "합성 구", "합성 지역", true));
        directory.saveRegion(new DirectoryService.Region(inactiveRegion, "HQ", "D2", "합성 구", "중지 지역", false));
        directory.saveRegion(new DirectoryService.Region(foreignRegion, otherOffice, "D3", "타 구", "타 지역", true));
        directory.savePerson(new DirectoryService.Person(username, "원본 이름", "HQ", "", "R1",
                "0211112222", "010-1111-2222", "Before@Example.COM", "기존 업무", true, Set.of(localRegion)));
    }

    @Test
    void applicantViewAndSubmitExposeEditableFieldsButPreserveIdentityAndOrganization() throws Exception {
        String authorityId = authority("AI_APP_" + suffix, "AI");

        mvc.perform(get(APPLICANT_URL).with(user(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rankCode").value("R1"))
                .andExpect(jsonPath("$.data.regionIds[0]").value(localRegion))
                .andExpect(jsonPath("$.data.availableRegions.length()").value(1))
                .andExpect(jsonPath("$.data.availableRegions[0].code").value(localRegion));

        Map<String, Object> applicant = applicant(Set.of(localRegion));
        applicant.put("username", "forged");
        applicant.put("name", "변조 이름");
        applicant.put("officeCode", "forged-office");
        applicant.put("departmentCode", "forged-department");
        applicant.put("active", false);
        submit(Set.of(authorityId), applicant, List.of()).andExpect(status().isOk());

        DirectoryService.Person saved = directory.person(username);
        assertThat(saved.username()).isEqualTo(username);
        assertThat(saved.name()).isEqualTo("원본 이름");
        assertThat(saved.officeCode()).isEqualTo("HQ");
        assertThat(saved.departmentCode()).isEmpty();
        assertThat(saved.active()).isTrue();
        assertThat(saved.rankCode()).isEqualTo("R2");
        assertThat(saved.email()).isEqualTo("changed@example.com");
    }

    @Test
    void applicantOnlyUpdateDoesNotInvalidateAuthorityCatalog() {
        long version = catalog.authorityVersion();

        DirectoryService.Person saved = directory.updateApplicant(username,
                new DirectoryService.ApplicantUpdate("R2", "0222223333", "01022223333",
                        "Changed@Example.COM", "변경 업무", Set.of(localRegion)));

        assertThat(catalog.authorityVersion()).isEqualTo(version);
        assertThat(saved.username()).isEqualTo(username);
        assertThat(saved.name()).isEqualTo("원본 이름");
        assertThat(saved.officeCode()).isEqualTo("HQ");
        assertThat(saved.departmentCode()).isEmpty();
        assertThat(saved.active()).isTrue();
    }

    @Test
    void foreignOrInactiveRegionLeavesStateUntouchedAndLegacyNullApplicantPreservesProfile() throws Exception {
        String authorityId = authority("AI_REG_" + suffix, "AI");
        Object before = directory.snapshotState();

        submit(Set.of(authorityId), applicant(Set.of(foreignRegion)), List.of())
                .andExpect(status().isConflict());
        assertThat(directory.snapshotState()).isEqualTo(before);
        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isZero();

        submit(Set.of(authorityId), applicant(Set.of(inactiveRegion)), List.of())
                .andExpect(status().isConflict());
        assertThat(directory.snapshotState()).isEqualTo(before);

        submit(Set.of(authorityId), null, List.of()).andExpect(status().isOk());
        assertThat(directory.snapshotState()).isEqualTo(before);
        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isEqualTo(1);
    }

    @Test
    void invalidOriginalContactFormatsLeaveEveryStateUntouched() throws Exception {
        String authorityId = authority("AI_FORMAT_" + suffix, "AI");
        Snapshot before = snapshot();

        Map<String, Object> wrongPrefix = applicant(Set.of(localRegion));
        wrongPrefix.put("mobile", "01122223333");
        submit(Set.of(authorityId), wrongPrefix, List.of()).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);

        Map<String, Object> hyphenated = applicant(Set.of(localRegion));
        hyphenated.put("mobile", "010-2222-3333");
        submit(Set.of(authorityId), hyphenated, List.of()).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);

        Map<String, Object> invalidEmail = applicant(Set.of(localRegion));
        invalidEmail.put("mobile", "01022223333");
        invalidEmail.put("email", "user@example.c");
        submit(Set.of(authorityId), invalidEmail, List.of()).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void laterAuthorityConflictRollsBackTermsApplicantRequestsHistoryCatalogAndAudit() throws Exception {
        String ai = authority("AI_ROLL_" + suffix, "AI");
        String ab = authority("AB_ROLL_" + suffix, "AB");
        long sequence = TERM_SEQUENCE.addAndGet(10);
        terms.importTerms(new AuthorityTermsService.ImportRequest(List.of(
                term(sequence, "AI"), term(sequence + 1, "AB"))));
        catalog.saveAssignment(username, ab, AuthorizationCatalog.AssignmentType.DIRECT,
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31), null);
        Snapshot before = snapshot();

        submit(Set.of(ai, ab), applicant(Set.of(localRegion)), List.of(
                decision(sequence, true), decision(sequence + 1, true)))
                .andExpect(status().isConflict());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(directory.person(username).rankCode()).isEqualTo("R1");
        assertThat(terms.agreements(username)).isEmpty();
        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isZero();
        assertThat(history.list(username, null, PageQuery.of(0, 100)).totalElements()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            Set<String> authorityIds, Map<String, Object> applicant, List<Map<String, Object>> decisions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "GRANT");
        body.put("authorityIds", authorityIds);
        body.put("reason", "합성 신청자 정보 저장");
        body.put("termDecisions", decisions);
        if (applicant != null) body.put("applicant", applicant);
        return mvc.perform(post(BATCH_URL).with(user(username)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private Map<String, Object> applicant(Set<String> regionIds) {
        return new LinkedHashMap<>(Map.of(
                "rankCode", "R2",
                "telephone", "0222223333",
                "mobile", "01022223333",
                "email", "Changed@Example.COM",
                "jobDescription", "변경 업무",
                "regionIds", regionIds));
    }

    private Map<String, Object> decision(long sequence, boolean agreed) {
        return Map.of("termSequence", sequence, "agreed", agreed);
    }

    private String authority(String id, String systemId) {
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(id, id, true, systemId, null, ""));
        return id;
    }

    private AuthorityTermsService.TermRow term(long sequence, String systemId) {
        return new AuthorityTermsService.TermRow(sequence, systemId, "01", "v1", "합성 약관", "본문",
                "02", null, null, "Y", 0, "Y");
    }

    private Snapshot snapshot() {
        return new Snapshot(directory.snapshotState(), terms.snapshotState(), catalog.snapshotState(),
                requests.snapshotState(), history.snapshotState(), audit.snapshotState());
    }

    private record Snapshot(Object directory, Object terms, Object catalog, Object requests, Object history, Object audit) { }
}
