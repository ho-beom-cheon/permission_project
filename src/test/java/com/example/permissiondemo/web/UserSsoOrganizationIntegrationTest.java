package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthoritySsoHistoryLinkService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UserSsoOrganizationIntegrationTest {
    private static final String AFFILIATIONS = "/api/admin/user-sso-organizations";
    private static final String LOOKUP = "/api/colleagues";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;
    @Autowired CommonCodeService codes;
    @Autowired DirectoryService directory;
    @Autowired AuthoritySsoHistoryLinkService links;
    private String key, actor, colleague, other, ssoA, ssoB;

    @BeforeEach
    void createSyntheticAffiliations() throws Exception {
        key = UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        actor = "actor_" + key; colleague = "peer_" + key; other = "other_" + key;
        ssoA = ("SSOA_" + key).toUpperCase(java.util.Locale.ROOT);
        ssoB = ("SSOB_" + key).toUpperCase(java.util.Locale.ROOT);
        catalog.saveAction(new AuthorizationCatalog.ProgramActionDefinition("SYSTEM_AUTH", "AUTHORITY", "DIRECTORY_LOOKUP", "업무담당자 조회", "btnDirectoryLookup", 30, true));
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition("LOOKUP_TEST", "합성 조회 권한", true));
        catalog.setMenuGrant("LOOKUP_TEST", "SYSTEM_AUTH", true);
        catalog.setActionGrant("LOOKUP_TEST", new AuthorizationCatalog.ProgramActionKey("SYSTEM_AUTH", "AUTHORITY", "DIRECTORY_LOOKUP"), true);
        for (String office : List.of("LOOKUP_A", "LOOKUP_B")) {
            save("/api/admin/directory/offices", Map.of("code", office, "name", office, "active", true), 200);
        }
        createPerson(actor, key + " Actor"); createPerson(colleague, key + " Alpha"); createPerson(other, key + " alpha");
        catalog.saveAssignment(actor, "LOOKUP_TEST", AuthorizationCatalog.AssignmentType.DIRECT, LocalDate.of(2000,1,1), LocalDate.of(2099,12,31), null);
        saveMapping(ssoA, "LOOKUP_A", "DeskAlpha", true, 0);
        saveMapping(ssoB, "LOOKUP_B", "DeskBeta", true, 0);
        save(AFFILIATIONS, affiliation(actor, ssoA, 0), 200);
        save(AFFILIATIONS, affiliation(colleague, ssoA, 0), 200);
        save(AFFILIATIONS, affiliation(other, ssoB, 0), 200);
        for (String username : List.of(actor, colleague, other)) {
            var assignment = catalog.assignmentsFor(username).stream().filter(item -> item.authorityId().equals("LOOKUP_TEST")).findFirst()
                    .orElseGet(() -> catalog.saveAssignment(username, "LOOKUP_TEST", AuthorizationCatalog.AssignmentType.DIRECT,
                            LocalDate.of(2000,1,1), LocalDate.of(2099,12,31), null));
            long version = links.list().stream().filter(item -> item.username().equals(username) && item.authorityId().equals("LOOKUP_TEST")
                    && item.organizationId().equals(assignment.organizationId()) && item.assignmentSequence() == assignment.sequence())
                    .mapToLong(AuthoritySsoHistoryLinkService.Link::version).findFirst().orElse(0);
            links.save(new AuthoritySsoHistoryLinkService.WriteLink(username, assignment.organizationId(), assignment.authorityId(), assignment.type(),
                    assignment.sequence(), username.equals(other) ? ssoB : ssoA, "TEAM", java.time.Instant.parse("2026-01-01T00:00:00Z"), version));
        }
    }

    @Test
    void branchScopeUsesExplicitSsoMappingAndCannotReadGlobalRegistry() throws Exception {
        JsonNode result = lookup(actor, get(LOOKUP).param("name", key));
        assertThat(result.path("headquarters").asBoolean()).isFalse();
        assertThat(result.path("officeCode").asText()).isEqualTo("LOOKUP_A");
        assertThat(result.path("people").toString()).contains(actor, colleague).doesNotContain(other);
        mvc.perform(get(LOOKUP).param("officeCode", "LOOKUP_B").with(user(actor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/directory").with(user(actor))).andExpect(status().isForbidden());
        assertThat(lookup("admin", get("/api/admin/directory")).path("people").toString()).contains(other);
    }

    @Test
    void headquartersMatchesActiveChildNameNotCodeAndIgnoresDisplayDates() throws Exception {
        setupBonbu();
        codes.saveItem("CM087", ssoA, "different_sso", "BONBU", 1, true, null, null);
        mvc.perform(get(LOOKUP).param("officeCode", "LOOKUP_B").with(user(actor))).andExpect(status().isForbidden());
        codes.saveItem("CM087", "CHILD_" + key, ssoA, "BONBU", 2, true, LocalDate.of(2090,1,1), LocalDate.of(2099,1,1));
        codes.saveGroup(new CommonCodeService.CodeGroup("CM087", "합성 본부 목록", "", false), codes.groupVersions().get("CM087"));
        JsonNode all = lookup(actor, get(LOOKUP).param("name", key));
        assertThat(all.path("headquarters").asBoolean()).isTrue();
        assertThat(all.path("people").toString()).contains(actor, colleague, other);
        assertThat(lookup(actor, get(LOOKUP).param("name", key).param("officeCode", "LOOKUP_B")).path("people").toString())
                .contains(other).doesNotContain(colleague);
        saveMapping(ssoB, "LOOKUP_B", "DeskBeta", false, 1);
        assertThat(lookup(actor, get(LOOKUP).param("name", key)).path("people").toString()).doesNotContain(other);
        codes.saveItem("CM087", "CHILD_" + key, ssoA, "BONBU", 2, false, null, null);
        mvc.perform(get(LOOKUP).param("officeCode", "LOOKUP_B").with(user(actor))).andExpect(status().isForbidden());
    }

    @Test
    void filtersUserAndSsoDepartmentNamesWithCaseSensitiveLike() throws Exception {
        assertThat(lookup(actor, get(LOOKUP).param("name", key + "%Alph_").param("ssoDepartmentName", "DeskA%"))
                .path("people").toString()).contains(colleague).doesNotContain(actor);
        assertThat(lookup(actor, get(LOOKUP).param("name", key).param("ssoDepartmentName", "deska%"))
                .path("people").isEmpty()).isTrue();
        assertThat(lookup(actor, get(LOOKUP).param("name", key).param("ssoDepartmentName", "Desk.*"))
                .path("people").isEmpty()).isTrue();
    }

    @Test
    void requiresAffiliationAndUsableMappingWithoutInferringHq() throws Exception {
        String missing = "missing_" + key;
        createPerson(missing, key + " Missing");
        catalog.saveAssignment(missing, "LOOKUP_TEST", AuthorizationCatalog.AssignmentType.DIRECT, LocalDate.of(2000,1,1), LocalDate.of(2099,12,31), null);
        mvc.perform(get(LOOKUP).with(user(missing))).andExpect(status().isForbidden());
        saveMapping(ssoA, "", "DeskAlpha", true, 1);
        mvc.perform(get(LOOKUP).with(user(actor))).andExpect(status().isForbidden());
        saveMapping(ssoA, "LOOKUP_A", "DeskAlpha", false, 2);
        mvc.perform(get(LOOKUP).with(user(actor))).andExpect(status().isForbidden());
        save(AFFILIATIONS, affiliation(missing, ssoA, 0), 409);
        save(AFFILIATIONS, affiliation(missing, "unknown", 0), 409);
        save(AFFILIATIONS, affiliation("unknown_user_" + key, ssoB, 0), 404);
    }

    @Test
    void affiliationChangesAreVersionedWithoutChangingExistingRightsOrLocalProfile() throws Exception {
        JsonNode beforeProfile = json.valueToTree(directory.person(actor));
        JsonNode beforeRights = json.valueToTree(catalog.snapshotState());
        save(AFFILIATIONS, affiliation(actor, ssoB, 1), 200);
        save(AFFILIATIONS, affiliation(actor, ssoA, 1), 409);
        assertThat(lookup(actor, get(LOOKUP)).path("officeCode").asText()).isEqualTo("LOOKUP_B");
        assertThat(json.<JsonNode>valueToTree(directory.person(actor))).isEqualTo(beforeProfile);
        assertThat(json.<JsonNode>valueToTree(catalog.snapshotState())).isEqualTo(beforeRights);
        byte[] body = json.writeValueAsBytes(affiliation(actor, ssoA, 2));
        mvc.perform(post(AFFILIATIONS).with(user(actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get(AFFILIATIONS).with(user(actor))).andExpect(status().isForbidden());
        mvc.perform(post(AFFILIATIONS).with(user("admin")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get(LOOKUP).with(user("viewer"))).andExpect(status().isForbidden());
        mvc.perform(get(LOOKUP).with(user("admin"))).andExpect(status().isForbidden());
    }

    @Test
    void missingStoredStateStartsEmpty() throws Exception {
        var isolated = new UserSsoOrganizationService(mock(DirectoryService.class), mock(OrganizationMappingService.class),
                mock(CommonCodeService.class), mock(CurrentUserContext.class), mock(AuditEventService.class),
                mock(AuthorizationCatalog.class), mock(AuthoritySsoHistoryLinkService.class));
        assertThat(isolated.list()).isEmpty();
        isolated.restoreState(json.readValue("{}", UserSsoOrganizationService.StoredState.class));
        assertThat(isolated.list()).isEmpty();
        isolated.restoreState(null);
        assertThat(isolated.list()).isEmpty();
    }

    @Test
    void historyLinkApiRequiresAdministratorCsrfAndSupportsLookupStatusFilters() throws Exception {
        var link = links.list().stream().filter(item -> item.username().equals(colleague)).findFirst().orElseThrow();
        String url = "/api/admin/authority-sso-history-links";
        var correction = new AuthoritySsoHistoryLinkService.WriteLink(link.username(), link.organizationId(), link.authorityId(),
                link.assignmentType(), link.assignmentSequence(), link.ssoOfficeCode(), link.ssoDepartmentCode(), link.registeredAt(), link.version());
        save(url, correction, 200);
        save(url, correction, 409);
        byte[] body = json.writeValueAsBytes(correction);
        mvc.perform(get(url).with(user(actor))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("admin")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(lookup("admin", get(url)).toString()).contains(colleague);
        assertThat(lookup(actor, get(LOOKUP).param("name", key + "%Alpha").param("authId", "LOOKUP_TEST")
                .param("procStatus", "APPROVED").param("systemPrefix", "LOOKUP")).path("people").toString()).contains(colleague);
        mvc.perform(get(LOOKUP).param("procStatus", "INVALID").with(user(actor))).andExpect(status().isBadRequest());
    }

    private void setupBonbu() {
        codes.saveGroup(new CommonCodeService.CodeGroup("CM087", "합성 본부 목록", "", true), codes.groupVersions().getOrDefault("CM087", 0L));
        codes.saveItem("CM087", "BONBU", "본부", 0, true);
    }

    private void createPerson(String username, String name) throws Exception {
        save("/api/admin/directory/people", Map.of("person", Map.of("username", username, "name", name, "officeCode", "HQ", "active", true)), 200);
    }

    private void saveMapping(String ssoOffice, String localOffice, String name, boolean active, long version) throws Exception {
        save("/api/admin/organization-mappings", new OrganizationMappingService.WriteMapping(ssoOffice, "TEAM", "합성 SSO사업소", name, localOffice, "", active, version), 200);
    }

    private UserSsoOrganizationService.WriteAffiliation affiliation(String username, String ssoOffice, long version) {
        return new UserSsoOrganizationService.WriteAffiliation(username, ssoOffice, "TEAM", version);
    }

    private JsonNode save(String url, Object body, int expectedStatus) throws Exception {
        var response = mvc.perform(post(url).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().is(expectedStatus)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private JsonNode lookup(String username, MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request.with(user(username))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }
}
