package com.example.permissiondemo.web;

import static org.hamcrest.Matchers.hasItem;
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
import org.springframework.test.web.servlet.MvcResult;

/** 권한 신청·승인 API가 CSRF, 관리자 기능 권한과 실제 효력 반영을 함께 지키는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthorityWorkflowSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestNeedsCsrfAndApprovalCreatesAuthority() throws Exception {
        String body = """
                {
                  "authorityId":"AUTH_CONTENT_MANAGER",
                  "assignmentType":"DIRECT",
                  "validFrom":"2026-09-01",
                  "validTo":"2026-12-31",
                  "reason":"콘텐츠 운영 지원"
                }
                """;

        mockMvc.perform(post("/api/authority-requests")
                        .with(user("viewer").roles("AUTHENTICATED_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        MvcResult submitted = mockMvc.perform(post("/api/authority-requests")
                        .with(user("viewer").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn();

        Number requestIdValue = com.jayway.jsonpath.JsonPath.read(
                submitted.getResponse().getContentAsString(), "$.data.id");
        long requestId = requestIdValue.longValue();

        mockMvc.perform(get("/api/admin/authority-requests")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/authority-requests/{id}/approve", requestId)
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"업무 근거 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(get("/api/me")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAuthorityIds",
                        hasItem("AUTH_CONTENT_MANAGER")));
    }

    @Test
    void requesterCanCancelOnlyPendingOwnRequest() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/authority-requests")
                        .with(user("viewer").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorityId":"AUTH_CONTENT_MANAGER",
                                  "assignmentType":"DIRECT",
                                  "validFrom":"2026-09-01",
                                  "validTo":"2026-12-31",
                                  "reason":"임시 운영 지원"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        Number requestIdValue = com.jayway.jsonpath.JsonPath.read(
                submitted.getResponse().getContentAsString(), "$.data.id");
        long requestId = requestIdValue.longValue();

        mockMvc.perform(post("/api/authority-requests/{id}/cancel", requestId)
                        .with(user("manager").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"타인 취소\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/authority-requests/{id}/cancel", requestId)
                        .with(user("viewer").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"업무 변경\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
