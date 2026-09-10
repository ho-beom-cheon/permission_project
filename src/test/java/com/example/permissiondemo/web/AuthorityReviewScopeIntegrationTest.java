package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 비본부 심사자는 신청 버전에 맞는 로컬 사업소의 신청만 조회·처리한다. */
@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorityReviewScopeIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AuthorizationCatalog catalog;
    @Autowired private AuthorityRequestService requests;
    @Autowired private DirectoryService directory;
    @Autowired private OrganizationMappingService mappings;
    @Autowired private UserSsoOrganizationService affiliations;
    @Autowired private SsoAuthorityScopeService scopes;

    @Test
    void legacyReviewerListsOnlyOwnOrganizationAndMixedReviewIsAtomic() throws Exception {
        reviewer("review_local_a", "LOCAL_A");
        catalog.saveUserProfile("app_local_a", "LOCAL_A", true);
        catalog.saveUserProfile("app_local_b", "LOCAL_B", true);
        var own = submit("app_local_a");
        var other = submit("app_local_b");

        assertThat(listedUsernames("review_local_a")).containsExactly("app_local_a");
        Object requestState = requests.snapshotState();
        Object catalogState = catalog.snapshotState();
        mvc.perform(post("/api/admin/authority-requests/review").with(user("review_local_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(List.of(own.id(), other.id()))))
                .andExpect(status().isForbidden());
        assertThat(requests.snapshotState()).isEqualTo(requestState);
        assertThat(catalog.snapshotState()).isEqualTo(catalogState);

        mvc.perform(post("/api/admin/authority-requests/review").with(user("review_local_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody(List.of(own.id()))))
                .andExpect(status().isOk());
        assertThat(requests.list("app_local_a", null, PageQuery.of(0, 10)).content().get(0).status())
                .isEqualTo(AuthorityRequestService.RequestStatus.REJECTED);
        assertThat(requests.list("app_local_b", null, PageQuery.of(0, 10)).content().get(0).status())
                .isEqualTo(AuthorityRequestService.RequestStatus.PENDING);
    }

    @Test
    void ssoReviewerUsesMappedLocalOfficeWhileAdminRemainsGlobal() throws Exception {
        office("REVIEW_LOCAL_A");
        office("REVIEW_LOCAL_B");
        ssoUser("review_sso_a", "REVIEWER_SSO", "REVIEWER_DEPT", "REVIEW_LOCAL_A", false);
        reviewer("review_sso_a", "HQ");
        ssoUser("app_sso_a", "APPLICANT_SSO", "DEPT_A", "REVIEW_LOCAL_A", true);
        ssoUser("app_sso_b", "APPLICANT_SSO", "DEPT_B", "REVIEW_LOCAL_A", true);
        ssoUser("app_sso_c", "OTHER_SSO", "DEPT_C", "REVIEW_LOCAL_B", true);
        var sameOfficeA = submit("app_sso_a");
        submit("app_sso_b");
        var otherOffice = submit("app_sso_c");

        assertThat(listedUsernames("review_sso_a")).containsExactlyInAnyOrder("app_sso_a", "app_sso_b");
        mappings.save(new OrganizationMappingService.WriteMapping("APPLICANT_SSO", "DEPT_B",
                "APPLICANT_SSO", "DEPT_B", "REVIEW_LOCAL_A", "", false, 1));
        assertThat(listedUsernames("review_sso_a")).containsExactly("app_sso_a");
        Object requestState = requests.snapshotState();
        Object catalogState = catalog.snapshotState();
        mvc.perform(post("/api/admin/authority-requests/review").with(user("review_sso_a")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(List.of(sameOfficeA.id(), otherOffice.id()))))
                .andExpect(status().isForbidden());
        assertThat(requests.snapshotState()).isEqualTo(requestState);
        assertThat(catalog.snapshotState()).isEqualTo(catalogState);
        assertThat(listedUsernames("admin")).contains("app_sso_a", "app_sso_b", "app_sso_c");
    }

    private AuthorityRequestService.AuthorityRequest submit(String username) {
        return requests.submit(username, AuthorityRequestService.RequestKind.GRANT,
                Set.of(AuthorizationCatalog.AUTH_CONTENT_MANAGER), null, null, "합성 심사 범위 신청");
    }

    private void reviewer(String username, String organizationId) {
        catalog.saveUserProfile(username, organizationId, true);
        catalog.saveAssignment(username, AuthorizationCatalog.AUTH_SYSTEM_ADMIN,
                AuthorizationCatalog.AssignmentType.DIRECT, LocalDate.now(), LocalDate.of(9999, 12, 31), null);
    }

    private void office(String code) {
        if (directory.view().offices().stream().noneMatch(item -> item.code().equals(code))) {
            directory.saveOffice(new DirectoryService.Office(code, code, "", "", "", true));
        }
    }

    private void ssoUser(String username, String office, String department, String localOffice, boolean enableMode) {
        if (mappings.list().stream().noneMatch(item -> item.ssoOfficeCode().equals(office)
                && item.ssoDepartmentCode().equals(department))) {
            mappings.save(new OrganizationMappingService.WriteMapping(office, department, office, department,
                    localOffice, "", true, 0));
        }
        if (directory.view().people().stream().noneMatch(person -> person.username().equals(username))) {
            directory.savePerson(new DirectoryService.Person(username, username, "HQ", "", "", "", "", "", "", true, Set.of()));
        }
        affiliations.save(new UserSsoOrganizationService.WriteAffiliation(username, office, department, 0));
        if (enableMode) scopes.save(new SsoAuthorityScopeService.WriteMode(username, true, 0));
    }

    private List<String> listedUsernames(String reviewer) throws Exception {
        var response = mvc.perform(get("/api/admin/authority-requests").with(user(reviewer)))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode content = json.readTree(response.getContentAsByteArray()).path("data").path("content");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        content.forEach(item -> result.add(item.path("username").asText()));
        return result;
    }

    private String reviewBody(List<Long> ids) throws Exception {
        return json.writeValueAsString(java.util.Map.of(
                "requestIds", ids, "decision", "REJECT", "reason", "범위 확인 반려"));
    }
}
