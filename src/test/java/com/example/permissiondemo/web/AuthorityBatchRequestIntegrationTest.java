package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityHistoryService;
import com.example.permissiondemo.authorization.AuthorityRequestService;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorityBatchRequestIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;
    @Autowired AuthorityRequestService requests;
    @Autowired DirectoryService directory;
    @Autowired AuthorityHistoryService history;
    @Autowired AuditEventService audit;

    String username;
    List<String> authorityIds;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        username = "batch_" + suffix.toLowerCase();
        authorityIds = List.of("AI_BATCH_B_" + suffix, "AI_BATCH_A_" + suffix,
                "CY_BATCH_" + suffix, "AB_BATCH_" + suffix);
        directory.savePerson(new DirectoryService.Person(
                username, username, "HQ", "", "", "", "", "", "", true, Set.of()));
    }

    @Test
    void batchCreatesOneRequestPerAuthorityInOriginalSystemAndIdOrder() throws Exception {
        saveAuthority(authorityIds.get(0), "AI", true);
        saveAuthority(authorityIds.get(1), "AI", true);
        saveAuthority(authorityIds.get(2), "CY", true);
        saveAuthority(authorityIds.get(3), "AB", true);

        mvc.perform(post("/api/me/authority-requests/batch").with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body("GRANT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].authorityIds[0]").value(authorityIds.get(1)))
                .andExpect(jsonPath("$.data[1].authorityIds[0]").value(authorityIds.get(0)))
                .andExpect(jsonPath("$.data[2].systemId").value("CY"))
                .andExpect(jsonPath("$.data[2].authorityIds[0]").value(authorityIds.get(2)))
                .andExpect(jsonPath("$.data[3].systemId").value("AB"))
                .andExpect(jsonPath("$.data[3].authorityIds[0]").value(authorityIds.get(3)))
                .andExpect(jsonPath("$.data[0].authorityIds.length()").value(1));
    }

    @Test
    void failureInLaterSystemRollsBackEarlierRequestAndPendingAssignment() throws Exception {
        authorityIds = List.of(authorityIds.get(0), authorityIds.get(3));
        saveAuthority(authorityIds.get(0), "AI", true);
        saveAuthority(authorityIds.get(1), "AB", false);
        long requestCount = requests.list(username, null, PageQuery.of(0, 100)).totalElements();
        int assignmentCount = catalog.assignmentsFor(username).size();
        long historyCount = history.list(username, null, PageQuery.of(0, 100)).totalElements();
        long auditCount = audit.findPage(PageQuery.of(0, 100)).totalElements();

        mvc.perform(post("/api/me/authority-requests/batch").with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body("GRANT"))))
                .andExpect(status().isConflict());

        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isEqualTo(requestCount);
        assertThat(catalog.assignmentsFor(username)).hasSize(assignmentCount)
                .noneMatch(item -> authorityIds.contains(item.authorityId()));
        assertThat(history.list(username, null, PageQuery.of(0, 100)).totalElements()).isEqualTo(historyCount);
        assertThat(audit.findPage(PageQuery.of(0, 100)).totalElements()).isEqualTo(auditCount);
    }

    @Test
    void batchRejectsChangeWithoutCreatingRequests() throws Exception {
        authorityIds = List.of(authorityIds.get(0));
        saveAuthority(authorityIds.get(0), "AI", true);
        long requestCount = requests.list(username, null, PageQuery.of(0, 100)).totalElements();

        mvc.perform(post("/api/me/authority-requests/batch").with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "kind", "CHANGE", "authorityIds", Set.copyOf(authorityIds), "reason", "변경 신청"))))
                .andExpect(status().isBadRequest());

        assertThat(requests.list(username, null, PageQuery.of(0, 100)).totalElements()).isEqualTo(requestCount);
    }

    private Map<String, Object> body(String kind) {
        return Map.of("kind", kind, "authorityIds", Set.copyOf(authorityIds), "reason", "다중 시스템 신청");
    }

    private void saveAuthority(String id, String systemId, boolean active) {
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(id, id, active, systemId, null, ""));
    }
}
