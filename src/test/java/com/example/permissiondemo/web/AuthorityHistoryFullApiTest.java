package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.storage.ComponentStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorityHistoryFullApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ComponentStateRepository repository;

    @Test
    void ownerCannotSpoofUserAndReadingOldRecordsDoesNotWriteState() throws Exception {
        var before = repository.load();
        long revision = repository.revision();
        var response = mvc.perform(get("/api/me/authority-history/full").with(user("viewer"))
                .param("username", "admin")).andExpect(status().isOk()).andReturn().getResponse();
        var rows = json.readTree(response.getContentAsByteArray()).path("data").path("content");
        assertThat(rows.isEmpty()).isFalse();
        rows.forEach(row -> assertThat(row.path("username").asText()).isEqualTo("viewer"));
        assertThat(repository.load()).isEqualTo(before);
        assertThat(repository.revision()).isEqualTo(revision);
    }

    @Test
    void fullHistoryRequiresAdminAndAppliesTargetAndStatus() throws Exception {
        mvc.perform(get("/api/admin/authority-history/full").with(user("viewer"))).andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/admin/authority-history/full").with(user("admin"))
                .param("username", "moved").param("status", "APPROVED"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var rows = json.readTree(response.getContentAsByteArray()).path("data").path("content");
        assertThat(rows.isEmpty()).isFalse();
        rows.forEach(row -> {
            assertThat(row.path("username").asText()).isEqualTo("moved");
            assertThat(row.path("status").asText()).isEqualTo("APPROVED");
        });
    }
}
