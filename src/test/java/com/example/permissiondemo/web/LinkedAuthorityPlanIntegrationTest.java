package com.example.permissiondemo.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LinkedAuthorityPlanIntegrationTest {

    private static final String USERNAME = "linked_sso_user";
    private static final String SOURCE = "AI9999999999";
    private static final String CY_TARGET = "CY0201004004";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;
    @Autowired AuthorityRequestService requests;
    @Autowired SsoAuthorityScopeService scopes;
    @Autowired DirectoryService directory;
    @Autowired OrganizationMappingService mappings;
    @Autowired UserSsoOrganizationService affiliations;

    @BeforeEach
    void fixture() {
        directory.savePerson(new DirectoryService.Person(
                USERNAME, "연계 SSO 사용자", "HQ", "", "", "", "", "", "", true, Set.of()));
        mappings.save(new OrganizationMappingService.WriteMapping(
                "SSO_LINKED", "610", "합성 SSO사업소", "행정지원과", "HQ", "", true, 0));
        affiliations.save(new UserSsoOrganizationService.WriteAffiliation(
                USERNAME, "SSO_LINKED", "610", 0));
        scopes.save(new SsoAuthorityScopeService.WriteMode(USERNAME, true, 0));
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                SOURCE, "AI 합성 선택 권한", true, "AI", null, ""));
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                CY_TARGET, "사업소민원처리", true, "CY", null, ""));
    }

    @Test
    void linkedPlanUsesExplicitSsoDepartmentAndExcludesPendingTargetInCurrentScope() throws Exception {
        mvc.perform(post("/api/me/authority-requests/linked-plan").with(user(USERNAME)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.automaticallyAddedAuthorityIds[0]").value(CY_TARGET))
                .andExpect(jsonPath("$.data.expandedAuthorityIds", org.hamcrest.Matchers.hasItem(CY_TARGET)));

        requests.submit(USERNAME, AuthorityRequestService.RequestKind.GRANT, Set.of(CY_TARGET),
                null, null, "이미 처리 대기 중인 자동 권한");

        mvc.perform(post("/api/me/authority-requests/linked-plan").with(user(USERNAME)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(planBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.automaticallyAddedAuthorityIds.length()").value(0))
                .andExpect(jsonPath("$.data.expandedAuthorityIds.length()").value(1))
                .andExpect(jsonPath("$.data.expandedAuthorityIds[0]").value(SOURCE));
    }

    private byte[] planBody() throws Exception {
        return json.writeValueAsBytes(Map.of("kind", "GRANT", "authorityIds", Set.of(SOURCE)));
    }
}
