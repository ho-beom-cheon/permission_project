package com.example.permissiondemo.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 네 관리 도메인이 각자 전용 프로그램 기능 권한으로 격리되는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagementRoleSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void programManagerCanManageProgramsButNotAuthorities() throws Exception {
        mockMvc.perform(get("/api/admin/programs")
                        .with(user("programManager").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        mockMvc.perform(post("/api/admin/programs/REPORTING")
                        .with(user("programManager").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"리포팅",
                                  "description":"운영 리포트 프로그램",
                                  "active":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("REPORTING"));

        mockMvc.perform(get("/api/admin/authorities")
                        .with(user("programManager").roles("AUTHENTICATED_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void codeManagerCanCreateCodeGroupButNotManageProjects() throws Exception {
        mockMvc.perform(post("/api/admin/common-code-groups/DELIVERY_TYPE")
                        .with(user("codeManager").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"배송 유형",
                                  "description":"배송 업무 분류",
                                  "sortOrder":200,
                                  "active":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupCode").value("DELIVERY_TYPE"));

        mockMvc.perform(get("/api/admin/projects")
                        .with(user("codeManager").roles("AUTHENTICATED_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void globalProjectManagerCanArchiveWithoutMembership() throws Exception {
        mockMvc.perform(post("/api/admin/projects/PRJ_ACCESS_RENEWAL/archive")
                        .with(user("projectManager").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"관리 기준에 따른 보관\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void authorityManagerCanReadApprovalQueue() throws Exception {
        mockMvc.perform(get("/api/admin/authority-requests")
                        .with(user("authorityManager").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }
}
