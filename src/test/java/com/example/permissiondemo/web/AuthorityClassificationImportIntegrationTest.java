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
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityClassification;
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
class AuthorityClassificationImportIntegrationTest {

    private static final String URL = "/api/admin/authority-classifications/import";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;

    @Test
    void requiresAuthorityUpdatePermission() throws Exception {
        StoredState before = state();

        mvc.perform(post(URL).with(user("viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(validPayload())))
                .andExpect(status().isForbidden());

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void requiresCsrfToken() throws Exception {
        StoredState before = state();

        mvc.perform(post(URL).with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(validPayload())))
                .andExpect(status().isForbidden());

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void rejectsWholePayloadBeforeChangingCatalog() throws Exception {
        StoredState before = state();
        Map<String, Object> invalid = Map.of(
                "classifications", List.of(
                        classification("AI9101", null, "정상 선행", "Y"),
                        classification("AI9102", "AI9999", "고아 후행", "Y")),
                "authorityLinks", List.of());

        mvc.perform(post(URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(invalid)))
                .andExpect(status().isBadRequest());

        assertThat(state()).isEqualTo(before);
    }

    @Test
    void importsTreeAndLinksWhilePreservingExistingState() throws Exception {
        String authorityId = "MO9100000001";
        catalog.saveAuthority(new AuthorityDefinition(authorityId, "합성 연결 대상", true, "MO", null, ""));
        StoredState before = state();
        Map<String, Object> payload = Map.of(
                "classifications", List.of(
                        classification("MO9102", "MO9101", "하위 합성 분류", "N"),
                        classification("MO9101", null, "상위 합성 분류", "N")),
                "authorityLinks", List.of(Map.of("authId", authorityId, "authClsfId", "MO9102")));

        mvc.perform(post(URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdClassifications[0]").value("MO9102"))
                .andExpect(jsonPath("$.data.createdClassifications[1]").value("MO9101"))
                .andExpect(jsonPath("$.data.createdAuthorityLinks[0]").value(authorityId));

        assertThat(catalog.classifications()).contains(
                new AuthorityClassification("MO9101", null, "상위 합성 분류", "MO", false),
                new AuthorityClassification("MO9102", "MO9101", "하위 합성 분류", "MO", false));
        assertThat(catalog.findAuthority(authorityId).orElseThrow().classificationId()).isEqualTo("MO9102");
        StoredState after = state();
        assertThat(after.assignments()).isEqualTo(before.assignments());
        assertThat(after.menus()).isEqualTo(before.menus());
        assertThat(after.menuGrants()).isEqualTo(before.menuGrants());
        assertThat(after.programs()).isEqualTo(before.programs());
        assertThat(after.actions()).isEqualTo(before.actions());
        assertThat(after.actionGrants()).isEqualTo(before.actionGrants());
        assertThat(after.authorityVersion()).isEqualTo(before.authorityVersion() + 1);
    }

    private Map<String, Object> validPayload() {
        return Map.of("classifications", List.of(classification("AI9199", null, "접근 검증", "Y")),
                "authorityLinks", List.of());
    }

    private Map<String, String> classification(String id, String parentId, String name, String useFlag) {
        var row = new java.util.LinkedHashMap<String, String>();
        row.put("authClsfId", id);
        row.put("upperAuthClsfId", parentId);
        row.put("authClsfNm", name);
        row.put("useFlag", useFlag);
        return row;
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private StoredState state() {
        return (StoredState) catalog.snapshotState();
    }
}
