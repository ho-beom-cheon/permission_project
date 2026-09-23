package com.example.permissiondemo.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 서버 목록과 자기 권한 신청 옵션의 공개 계약을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminListApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminListsSupportFilteringSortingAndPaging() throws Exception {
        mockMvc.perform(get("/api/admin/authorities")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("active", "true")
                        .param("sort", "NAME")
                        .param("direction", "ASC")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").isNumber());

        mockMvc.perform(get("/api/admin/program-actions")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("keyword", "AUTHORITY")
                        .param("sort", "ACTION")
                        .param("direction", "DESC")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/admin/user-authorities")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("keyword", "AUTH_")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/admin/menus")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("displayed", "true")
                        .param("sort", "ORDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/admin/programs")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("active", "true")
                        .param("sort", "NAME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/admin/projects")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "ID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projects").isArray());

        mockMvc.perform(get("/api/admin/common-codes/ARTICLE_STATUS")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("current", "true")
                        .param("sort", "ORDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void invalidSortAndPageSizeReturnStandardBadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/authorities")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("sort", "UNSAFE_COLUMN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());

        mockMvc.perform(get("/api/admin/authorities")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/audit-events")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .param("from", "2026-08-25T14:22:57.625Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("from 값이 올바르지 않습니다."));
    }

    @Test
    void ordinaryUserCanReadRequestOptionsButNotAdminLists() throws Exception {
        mockMvc.perform(get("/api/authority-requests/options")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorities").isArray())
                .andExpect(jsonPath("$.data.assignmentTypes").isArray());

        mockMvc.perform(get("/api/admin/authorities")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditUsesDedicatedProgramPermission() throws Exception {
        mockMvc.perform(get("/api/audit-events")
                        .with(user("auditor").roles("AUTHENTICATED_USER"))
                        .param("sort", "OCCURRED_AT")
                        .param("direction", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(get("/api/audit-events")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isForbidden());
    }
}
