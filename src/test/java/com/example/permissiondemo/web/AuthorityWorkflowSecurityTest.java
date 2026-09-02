package com.example.permissiondemo.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.example.permissiondemo.PermissionDemoApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 본인 신청·관리자 심사·CSRF를 실제 필터 체인에서 검증한다. 실제 DB를 사용하지 않는다. */
@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorityWorkflowSecurityTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void requesterCannotForgeOwnerOrReviewAndApprovalChangesRuntimePermission() throws Exception {
        String request = """
                {"kind":"GRANT","authorityIds":["AUTH_CONTENT_MANAGER"],
                 "validFrom":"2026-01-01","validTo":"2099-12-31","reason":"가상 업무 테스트",
                 "username":"admin","organizationId":"FORGED"}
                """;
        mvc.perform(get("/api/me/authority-requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/authority-requests").with(user("viewer"))
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        var result = mvc.perform(post("/api/me/authority-requests").with(user("viewer")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("viewer"))
                .andExpect(jsonPath("$.data.organizationId").value("HQ")).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(get("/api/me/authority-requests").with(user("manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/admin/authority-requests").with(user("viewer"))).andExpect(status().isForbidden());
        String review = "{\"requestIds\":[" + id + "],\"decision\":\"APPROVE\",\"reason\":\"확인\"}";
        mvc.perform(post("/api/admin/authority-requests/review").with(user("viewer")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isForbidden());
        mvc.perform(post("/api/content/save").with(user("viewer")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/authority-requests/review").with(user("admin"))
                .contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/authority-requests/review").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isOk());
        mvc.perform(post("/api/content/save").with(user("viewer")).with(csrf())).andExpect(status().isOk());
        var notices=mvc.perform(get("/api/me/notices").with(user("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1)).andReturn();
        long noticeId=json.readTree(notices.getResponse().getContentAsByteArray()).path("data").path("content").get(0).path("id").asLong();
        mvc.perform(get("/api/me/notices").with(user("manager"))).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(post("/api/me/notices/"+noticeId+"/read").with(user("manager")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/me/notices/"+noticeId+"/read").with(user("viewer")).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/me/notices/unread-count").with(user("viewer"))).andExpect(jsonPath("$.data.count").value(0));
    }
}
