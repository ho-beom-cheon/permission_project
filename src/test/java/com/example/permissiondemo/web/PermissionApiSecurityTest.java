package com.example.permissiondemo.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.permissiondemo.PermissionDemoApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 경계에서 인증, 업무 인가, CSRF, 입력 검증, 오류 봉투, 세션 종료를 통합 검증한다.
 * 서비스 단위 테스트만으로 놓치기 쉬운 Spring Security 필터와 컨트롤러 연결을 실제 요청 형태로 확인한다.
 */
@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
class PermissionApiSecurityTest {

    /** 실제 서블릿 필터 체인을 거치는 요청을 만들기 위한 테스트 클라이언트다. */
    @Autowired
    private MockMvc mockMvc;

    /** 로그인 응답에서 CSRF 메타데이터를 읽어 후속 실제 세션 요청에 사용한다. */
    @Autowired
    private ObjectMapper objectMapper;

    /** 익명 API 요청이 HTML 리다이렉트가 아닌 추적 가능한 401 JSON 오류로 끝나는지 확인한다. */
    @Test
    void anonymousApiRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/menus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.path").value("/api/menus"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());
    }

    /** 브라우저 기본 favicon 요청이 불필요한 404 오류를 만들지 않는지 확인한다. */
    @Test
    void faviconDoesNotCreateConsoleError() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNoContent());
    }

    /** 익명 사용자에게 실제 로그인 화면과 폼 전송용 CSRF 토큰을 제공하는지 확인한다. */
    @Test
    void loginPageAndCsrfArePublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/login.html"));

        mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ARISU Works")))
                .andExpect(content().string(containsString("loginForm")));

        mockMvc.perform(get("/login-polish.css"))
                .andExpect(status().isOk())
                // MockMvc 정적 CSS 응답은 charset이 없을 수 있으므로 한글 주석 대신 실제 선택자를 확인한다.
                .andExpect(content().string(containsString(".login-card h2")));

        mockMvc.perform(get("/api/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    /** 사용자 업무 포털은 익명 접근을 차단하고 로그인 사용자는 정적 화면을 받을 수 있는지 확인한다. */
    @Test
    void userPortalRequiresLogin() throws Exception {
        mockMvc.perform(get("/portal.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        mockMvc.perform(get("/portal.html")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ARISU Works")))
                .andExpect(content().string(containsString("portal-main")));
    }

    /** 로그인 상태의 존재하지 않는 리소스가 표준 404 오류 코드로 변환되는지 확인한다. */
    @Test
    @WithMockUser(username = "viewer", roles = "AUTHENTICATED_USER")
    void missingResourceReturnsNotFound() throws Exception {
        mockMvc.perform(get("/missing-resource.js").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    /** 초기 화면에 필요한 권한·메뉴·코드·CSRF가 하나의 일관된 스냅샷으로 오는지 확인한다. */
    @Test
    @WithMockUser(username = "viewer", roles = "AUTHENTICATED_USER")
    void bootstrapReturnsOnePermissionSnapshot() throws Exception {
        mockMvc.perform(get("/api/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.context.user.username").value("viewer"))
                .andExpect(jsonPath(
                        "$.data.context.programActions[*].actionId",
                        contains("CONTENT_READ")))
                .andExpect(jsonPath("$.data.context.codeGroups.USE_YN.version").isNumber())
                .andExpect(jsonPath("$.data.context.versions.programVersion").isNumber())
                .andExpect(jsonPath("$.data.context.codeGroups.REGION.items[*].parentCode",
                        hasItem("SEOUL")))
                .andExpect(jsonPath("$.data.csrf.token").isNotEmpty());
    }

    /** 요청 파라미터로 관리자 권한이나 역할을 위조해도 서버 계산 권한이 상승하지 않는지 확인한다. */
    @Test
    @WithMockUser(username = "viewer", roles = "AUTHENTICATED_USER")
    void clientSuppliedAuthorityIdsCannotEscalatePermission() throws Exception {
        mockMvc.perform(get("/api/menus/CONTENT_LIST/programs/CONTENT/actions")
                        .param("authIds", "AUTH_SYSTEM_ADMIN")
                        .param("role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].actionId", contains("CONTENT_READ")))
                .andExpect(jsonPath("$.data[*].actionId", not(hasItem("CONTENT_SAVE"))));

        mockMvc.perform(get("/api/admin/authorities"))
                .andExpect(status().isForbidden());
    }

    /** 올바른 CSRF 토큰이 있어도 업무 기능 권한이 없으면 게시가 거부되는지 확인한다. */
    @Test
    @WithMockUser(username = "viewer", roles = "AUTHENTICATED_USER")
    void viewerCannotPublishEvenWithValidCsrf() throws Exception {
        mockMvc.perform(post("/api/content/publish").with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** 쓰기 권한과 별개로 상태 변경 요청에는 유효한 CSRF 토큰도 필요한지 확인한다. */
    @Test
    @WithMockUser(username = "manager", roles = "AUTHENTICATED_USER")
    void managerNeedsCsrfForWriteRequest() throws Exception {
        mockMvc.perform(post("/api/content/publish"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/content/publish").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** 공통코드 변경 권한을 가진 관리자 요청만 저장 계약을 통과하는지 확인한다. */
    @Test
    @WithMockUser(username = "admin", roles = "AUTHENTICATED_USER")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void onlyAuthorizedAdminCanSaveCommonCode() throws Exception {
        mockMvc.perform(post("/api/common-codes/USE_YN/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TEST\",\"name\":\"테스트\",\"sortOrder\":80,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("TEST"));
    }

    /** 일반 사용자가 권한·메뉴·공통코드의 관리자 원본 조회 API에 접근할 수 없는지 확인한다. */
    @Test
    @WithMockUser(username = "viewer", roles = "AUTHENTICATED_USER")
    void viewerCannotReadManagementViews() throws Exception {
        mockMvc.perform(get("/api/admin/authority-view"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/menu-view"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/program-view"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/common-code-view"))
                .andExpect(status().isForbidden());
    }

    /** 관리자 변경 요청의 CSRF 검사와 직접 권한 부여·회수의 실제 유효 권한 반영을 확인한다. */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void adminCanAssignAndRevokeAuthority() throws Exception {
        String request = "{\"authorityId\":\"AUTH_CONTENT_MANAGER\",\"type\":\"DIRECT\","
                + "\"validFrom\":\"2020-01-01\",\"validTo\":\"2099-12-31\"}";

        mockMvc.perform(post("/api/admin/users/viewer/authorities")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/users/viewer/authorities")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(get("/api/me")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAuthorityIds",
                        hasItem("AUTH_CONTENT_MANAGER")));

        mockMvc.perform(delete("/api/admin/users/viewer/authorities/AUTH_CONTENT_MANAGER")
                        .param("type", "DIRECT")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mockMvc.perform(get("/api/me")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAuthorityIds",
                        not(hasItem("AUTH_CONTENT_MANAGER"))));
    }

    /** 메뉴 신규 저장과 권한별 직접 매핑이 해당 사용자의 서버 메뉴 트리에 반영되는지 확인한다. */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void adminCanSaveAndGrantMenu() throws Exception {
        mockMvc.perform(post("/api/admin/menus/REPORT_TEST")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"CONTENT\",\"name\":\"보고서 테스트\","
                                + "\"path\":\"#home\",\"sortOrder\":90,\"active\":true,"
                                + "\"displayed\":true,\"publicMenu\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("REPORT_TEST"));

        mockMvc.perform(put("/api/admin/authorities/AUTH_VIEWER/menus/REPORT_TEST")
                        .with(user("admin").roles("AUTHENTICATED_USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasItem("REPORT_TEST")));

        mockMvc.perform(get("/api/menus")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].children[*].id", hasItem("REPORT_TEST")));
    }

    /** 권한·프로그램·기능·매핑을 등록하면 대상 사용자의 실제 기능 인가에 반영되는지 확인한다. */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void adminCanManageProgramPermissions() throws Exception {
        var admin = user("admin").roles("AUTHENTICATED_USER");

        mockMvc.perform(post("/api/admin/authorities/AUTH_REPORT_MANAGER")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"보고서 관리자\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("AUTH_REPORT_MANAGER"));

        mockMvc.perform(post("/api/admin/menus/REPORT_LIST")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"CONTENT\",\"name\":\"보고서 조회\"," 
                                + "\"path\":\"#report\",\"sortOrder\":80,\"active\":true,"
                                + "\"displayed\":true,\"publicMenu\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/programs/REPORT")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"보고서 관리\",\"description\":\"보고서 조회와 출력\"," 
                                + "\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("REPORT"));

        mockMvc.perform(post("/api/admin/program-actions/REPORT_LIST/REPORT/REPORT_READ")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"보고서 조회\",\"componentId\":\"btnReportRead\"," 
                                + "\"sortOrder\":10,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionId").value("REPORT_READ"));

        mockMvc.perform(put("/api/admin/authorities/AUTH_REPORT_MANAGER/menus/REPORT_LIST")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/authorities/AUTH_REPORT_MANAGER/program-actions/"
                        + "REPORT_LIST/REPORT/REPORT_READ")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granted\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users/viewer/authorities")
                        .with(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorityId\":\"AUTH_REPORT_MANAGER\",\"type\":\"DIRECT\"," 
                                + "\"validFrom\":\"2020-01-01\",\"validTo\":\"2099-12-31\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/menus/REPORT_LIST/programs/REPORT/actions")
                        .with(user("viewer").roles("AUTHENTICATED_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].actionId", contains("REPORT_READ")));

        mockMvc.perform(get("/api/admin/program-view").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.programs[*].id", hasItem("REPORT")))
                .andExpect(jsonPath("$.data.version").isNumber());
    }

    /** 관심 메뉴 등록 시 현재 메뉴 접근권한을 재검사하고 삭제도 CSRF로 보호하는지 확인한다. */
    @Test
    @WithMockUser(username = "viewer", roles = "AUTHENTICATED_USER")
    void favoriteMenuRequiresCurrentAccess() throws Exception {
        mockMvc.perform(post("/api/me/favorite-menus/CONTENT_LIST").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuId").value("CONTENT_LIST"));

        mockMvc.perform(post("/api/me/favorite-menus/SYSTEM_AUTH").with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/me/favorite-menus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].menuId", contains("CONTENT_LIST")));

        mockMvc.perform(delete("/api/me/favorite-menus/CONTENT_LIST").with(csrf()))
                .andExpect(status().isOk());
    }

    /** Bean Validation 오류가 필드 목록과 장애 추적용 traceId를 함께 제공하는지 확인한다. */
    @Test
    @WithMockUser(username = "admin", roles = "AUTHENTICATED_USER")
    void validationErrorIncludesFieldAndTraceId() throws Exception {
        mockMvc.perform(post("/api/common-codes/USE_YN/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"../bad\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.fieldErrors[*].field", hasItem("code")))
                .andExpect(jsonPath("$.error.fieldErrors[*].field", hasItem("name")));
    }

    /** 감사 이력이 무제한 배열이 아니라 page/size가 포함된 제한된 페이지 계약인지 확인한다. */
    @Test
    @WithMockUser(username = "admin", roles = "AUTHENTICATED_USER")
    void auditEventsUseBoundedPagingContract() throws Exception {
        mockMvc.perform(post("/api/me/favorite-menus/SYSTEM_AUTH").with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/audit-events").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.content[*].eventType",
                        hasItem("FAVORITE_MENU_ADDED")));
    }

    /**
     * mock 사용자 주입이 아닌 실제 폼 로그인을 수행하여 세션 생성, CSRF 조회, 보호 요청,
     * 로그아웃 후 세션 무효화까지 전체 수명주기를 검증한다.
     */
    @Test
    void realFormLoginSessionCsrfAndLogoutFlowWorks() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin()
                        .user("manager")
                        .password("manager123!"))
                .andExpect(authenticated().withUsername("manager"))
                .andExpect(redirectedUrl("/portal.html"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        MvcResult csrfResult = mockMvc.perform(get("/api/csrf").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headerName").isNotEmpty())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        JsonNode csrfData = objectMapper.readTree(csrfResult.getResponse().getContentAsString())
                .path("data");
        String headerName = csrfData.path("headerName").asText();
        String token = csrfData.path("token").asText();

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/content/publish")
                        .session(session)
                        .header(headerName, token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/logout")
                .session(session)
                .header(headerName, token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(unauthenticated());
        assertThat(session.isInvalid()).isTrue();
    }
}
