package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.authorization.AuthorizationCatalog.StoredState;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AuthorityMasterImportIntegrationTest {

    private static final String URL = "/api/admin/authorities/import";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;

    @Test
    void requiresAuthorityUpdatePermission() throws Exception {
        StoredState before = state();

        mvc.perform(post(URL).with(user("viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(row("AI9000000001", "접근 거부 행", "Y"))))
                .andExpect(status().isForbidden());

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void requiresCsrfToken() throws Exception {
        StoredState before = state();

        mvc.perform(post(URL).with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(row("AI9000000002", "CSRF 거부 행", "Y"))))
                .andExpect(status().isForbidden());

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void rejectsWholeBatchBeforeChangingCatalog() throws Exception {
        StoredState before = state();

        mvc.perform(post(URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                row("AI9000000003", "정상 선행 행", "Y"),
                                row("ZZ9000000004", "잘못된 후행 행", "Y"))))
                .andExpect(status().isBadRequest());

        assertThat(catalog.findAuthority("AI9000000003")).isEmpty();
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void importsExactRowsAndPreservesGenericStateAndMappings() throws Exception {
        StoredState before = state();
        List<AuthorityDefinition> genericAuthorities = before.authorities().stream()
                .filter(item -> item.id().startsWith("AUTH_"))
                .toList();

        mvc.perform(post(URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(
                                Map.of("authId", "AI9000000005", "authNm", "합성 검증 권한",
                                        "authDsc", "통합 테스트 값", "useFlag", "Y", "authClsfId", "AI_TEST"),
                                row("CY9000000006", "비활성 합성 권한", "N"),
                                row("MO9000000007", "모바일 합성 권한", "Y"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created[0]").value("AI9000000005"))
                .andExpect(jsonPath("$.data.created[1]").value("CY9000000006"))
                .andExpect(jsonPath("$.data.created[2]").value("MO9000000007"))
                .andExpect(jsonPath("$.data.deferredClassificationIds.AI9000000005").value("AI_TEST"));

        assertThat(catalog.findAuthority("AI9000000005")).contains(
                new AuthorityDefinition("AI9000000005", "합성 검증 권한", true, "AI", null, "통합 테스트 값"));
        assertThat(catalog.findAuthority("CY9000000006")).contains(
                new AuthorityDefinition("CY9000000006", "비활성 합성 권한", false, "CY", null, ""));
        assertThat(catalog.findAuthority("MO9000000007")).contains(
                new AuthorityDefinition("MO9000000007", "모바일 합성 권한", true, "MO", null, ""));

        StoredState after = state();
        assertThat(after.authorities()).containsAll(genericAuthorities);
        assertThat(after.users()).isEqualTo(before.users());
        assertThat(after.assignments()).isEqualTo(before.assignments());
        assertThat(after.menus()).isEqualTo(before.menus());
        assertThat(after.menuGrants()).isEqualTo(before.menuGrants());
        assertThat(after.programs()).isEqualTo(before.programs());
        assertThat(after.actions()).isEqualTo(before.actions());
        assertThat(after.actionGrants()).isEqualTo(before.actionGrants());
    }

    @SafeVarargs
    private final String body(Map<String, String>... rows) throws Exception {
        return json.writeValueAsString(List.of(rows));
    }

    private Map<String, String> row(String id, String name, String useFlag) {
        return Map.of("authId", id, "authNm", name, "authDsc", "", "useFlag", useFlag);
    }

    private StoredState state() {
        return (StoredState) catalog.snapshotState();
    }
}
