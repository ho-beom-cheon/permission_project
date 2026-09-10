package com.example.permissiondemo.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import com.example.permissiondemo.PermissionDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 신규 요청의 전이 규칙과 적용 기간은 클라이언트가 이전 버전으로 되돌릴 수 없다. */
@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorityWorkflowVersionTest {
    @Autowired MockMvc mvc;
    @Autowired Clock clock;

    @Test
    void clientCannotSelectLegacyWorkflowOrOverrideSourcePeriod() throws Exception {
        mvc.perform(post("/api/me/authority-requests").with(user("viewer")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"GRANT","authorityIds":["AUTH_CONTENT_MANAGER"],"reason":"서버 규칙 검증",
                 "workflowVersion":1,"validFrom":"2000-01-01","validTo":"2000-01-02"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workflowVersion").value(2))
                .andExpect(jsonPath("$.data.validFrom").value(LocalDate.now(clock).toString()))
                .andExpect(jsonPath("$.data.validTo").value("9999-12-31"));
    }

    @Test
    void newUiCanSubmitWithoutPeriodAndChangeRejectsMultipleTargets() throws Exception {
        mvc.perform(post("/api/me/authority-requests").with(user("moved")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"CHANGE","authorityIds":["AUTH_VIEWER","AUTH_CONTENT_MANAGER"],"reason":"복수 변경"}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/authority-requests").with(user("moved")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"GRANT","authorityIds":["AUTH_CONTENT_MANAGER"],"reason":"기간 자동 결정"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workflowVersion").value(2));
    }
}
