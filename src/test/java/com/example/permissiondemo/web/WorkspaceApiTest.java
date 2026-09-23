package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorizationCatalog.MenuDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 화면 분리와 실제 관리 설정이 사용자별 적용 현황으로 이어지는 공개 계약을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthorizationCatalog catalog;

    @Test
    void defaultEntryIsPortalAndAnonymousRequestsRequireLogin() throws Exception {
        mvc.perform(get("/").with(user("viewer")))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/portal.html"));
        mvc.perform(get("/portal").with(user("viewer")))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/portal.html"));
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/portal/bootstrap")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/bootstrap")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotEnterAdminThroughAnyHtmlAliasOrBootstrap() throws Exception {
        for (String path : new String[]{"/admin", "/admin/", "/index.html", "/api/admin/bootstrap"}) {
            mvc.perform(get(path).with(user("viewer"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void domainManagerCanEnterAdminWithoutBeingSystemAdministrator() throws Exception {
        mvc.perform(get("/admin").with(user("programManager")))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        JsonNode context = data("/api/admin/bootstrap", "programManager").path("context");
        assertThat(context.path("menus").toString()).contains("SYSTEM_PROGRAM", "SYSTEM_MENU")
                .doesNotContain("SYSTEM_AUTH_MASTER", "CONTENT_LIST", "MY_AUTH_REQUEST");
    }

    @Test
    void adminAndUserBootstrapsHaveSeparateMenusAndActionContexts() throws Exception {
        JsonNode workspace = portal("admin");
        assertThat(workspace.path("adminAvailable").asBoolean()).isTrue();
        assertThat(workspace.path("context").path("menus").toString())
                .contains("CONTENT_LIST", "MY_AUTH_REQUEST").doesNotContain("SYSTEM");
        assertThat(workspace.path("context").path("programActions").toString()).doesNotContain("SYSTEM");
        JsonNode admin = data("/api/admin/bootstrap", "admin").path("context");
        assertThat(admin.path("menus").toString()).contains("SYSTEM_AUTH_MASTER", "SYSTEM_PROGRAM")
                .doesNotContain("CONTENT_LIST", "MY_AUTH_REQUEST", "#home");
        assertThat(admin.path("programActions").toString()).doesNotContain("CONTENT_LIST");
    }

    @Test
    void viewerSeesAuthorityNamesAndAllowedAndDeniedProgramActions() throws Exception {
        JsonNode workspace = portal("viewer");
        assertThat(workspace.path("adminAvailable").asBoolean()).isFalse();
        assertThat(workspace.path("authorities").toString()).contains("AUTH_VIEWER", "조회 사용자")
                .doesNotContain("AUTH_CONTENT_MANAGER");
        JsonNode service = service(workspace, "CONTENT_LIST");
        assertThat(service.path("grantedBy").toString()).contains("조회 사용자");
        JsonNode program = service.path("programs").get(0);
        assertThat(program.path("name").asText()).isEqualTo("콘텐츠 관리");
        assertThat(action(program, "CONTENT_READ").path("allowed").asBoolean()).isTrue();
        assertThat(action(program, "CONTENT_SAVE").path("allowed").asBoolean()).isFalse();
    }

    @Test
    void menuGrantAloneDoesNotAllowAdminEntry() throws Exception {
        catalog.setMenuGrant("AUTH_VIEWER", "SYSTEM_CODE", true);
        mvc.perform(get("/admin").with(user("viewer"))).andExpect(status().isForbidden());
        assertThat(portal("viewer").path("adminAvailable").asBoolean()).isFalse();
        catalog.setMenuGrant("AUTH_VIEWER", "SYSTEM_CODE", false);
    }

    @Test
    void registeredMenuProgramAndGrantsReflectChangesWithoutRestart() throws Exception {
        save("/menus/WORK_REPORT", """
                {"parentId":"CONTENT","name":"업무 보고","path":"#work-report","sortOrder":70,
                 "active":true,"displayed":true,"publicMenu":false}
                """);
        save("/programs/WORK_REPORT", """
                {"name":"업무 보고 프로그램","description":"등록 확인용","active":true}
                """);
        save("/program-actions/WORK_REPORT/WORK_REPORT/READ", """
                {"label":"보고 조회","componentId":null,"sortOrder":10,"active":true}
                """);
        save("/program-actions/WORK_REPORT/WORK_REPORT/WRITE", """
                {"label":"보고 작성","componentId":null,"sortOrder":20,"active":true}
                """);
        assertThat(portal("viewer").path("services").toString()).doesNotContain("WORK_REPORT");
        grant("/menus/WORK_REPORT", true);
        grant("/program-actions/WORK_REPORT/WORK_REPORT/READ", true);
        JsonNode program = service(portal("viewer"), "WORK_REPORT").path("programs").get(0);
        assertThat(program.path("name").asText()).isEqualTo("업무 보고 프로그램");
        assertThat(action(program, "READ").path("allowed").asBoolean()).isTrue();
        assertThat(action(program, "READ").path("grantedBy").toString()).contains("조회 사용자");
        assertThat(action(program, "WRITE").path("allowed").asBoolean()).isFalse();

        grant("/program-actions/WORK_REPORT/WORK_REPORT/READ", false);
        program = service(portal("viewer"), "WORK_REPORT").path("programs").get(0);
        assertThat(action(program, "READ").path("allowed").asBoolean()).isFalse();
        save("/programs/WORK_REPORT", """
                {"name":"업무 보고 프로그램","description":"등록 확인용","active":false}
                """);
        program = service(portal("viewer"), "WORK_REPORT").path("programs").get(0);
        assertThat(program.path("active").asBoolean()).isFalse();
        assertThat(action(program, "READ").path("active").asBoolean()).isFalse();
        catalog.saveMenu(new MenuDefinition("WORK_REPORT", "CONTENT", "업무 보고",
                "#work-report", 70, false, true, false));
        assertThat(portal("viewer").path("services").toString()).doesNotContain("WORK_REPORT");
        catalog.saveMenu(new MenuDefinition("WORK_REPORT", "CONTENT", "업무 보고",
                "#work-report", 70, true, true, false));
        grant("/menus/WORK_REPORT", false);
        assertThat(portal("viewer").path("services").toString()).doesNotContain("WORK_REPORT");
    }

    @Test
    void revokedAuthorityCannotAppearAsEffectiveOrAllowAdminEntry() throws Exception {
        JsonNode workspace = portal("revoked");
        assertThat(workspace.path("authorities")).isEmpty();
        assertThat(workspace.path("services").toString()).doesNotContain("CONTENT_LIST", "WORK_REPORT");
        assertThat(workspace.path("context").path("programActions")).isEmpty();
        assertThat(workspace.path("adminAvailable").asBoolean()).isFalse();
        mvc.perform(get("/admin").with(user("revoked"))).andExpect(status().isForbidden());
    }

    @Test
    void navigationAncestorDoesNotBecomeAnAccessibleService() throws Exception {
        catalog.saveMenu(new MenuDefinition("USER_FOLDER", null, "상위 폴더",
                "#private-folder", 90, true, true, false));
        catalog.saveMenu(new MenuDefinition("PUBLIC_CHILD", "USER_FOLDER", "공용 업무",
                "#public-child", 10, true, true, true));
        JsonNode workspace = portal("viewer");
        assertThat(workspace.path("context").path("menus").toString()).contains("USER_FOLDER")
                .doesNotContain("#private-folder");
        assertThat(workspace.path("services").toString()).contains("PUBLIC_CHILD")
                .doesNotContain("USER_FOLDER");
        catalog.saveMenu(new MenuDefinition("PUBLIC_CHILD", "USER_FOLDER", "공용 업무",
                "#public-child", 10, false, true, true));
        catalog.saveMenu(new MenuDefinition("USER_FOLDER", null, "상위 폴더",
                "#private-folder", 90, false, true, false));
    }

    @Test
    void hiddenMenusAndCustomSystemDescendantsNeverLeakIntoUserServices() throws Exception {
        catalog.saveMenu(new MenuDefinition("INTERNAL_TOOL", "SYSTEM", "내부 관리 도구",
                "#internal-tool", 90, true, true, true));
        catalog.saveMenu(new MenuDefinition("USER_HIDDEN", "CONTENT", "숨긴 업무",
                "#user-hidden", 90, true, false, true));
        assertThat(portal("admin").path("services").toString())
                .doesNotContain("INTERNAL_TOOL", "USER_HIDDEN", "HIDDEN_MENU");
        assertThat(data("/api/admin/bootstrap", "admin").path("context").path("menus").toString())
                .contains("INTERNAL_TOOL");
    }

    private JsonNode data(String path, String username) throws Exception {
        return mapper.readTree(mvc.perform(get(path).with(user(username))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private JsonNode portal(String username) throws Exception {
        return data("/api/portal/bootstrap", username).path("workspace");
    }

    private JsonNode service(JsonNode workspace, String id) {
        for (JsonNode service : workspace.path("services")) {
            if (id.equals(service.path("id").asText())) return service;
        }
        throw new AssertionError("업무 메뉴 없음: " + id);
    }

    private JsonNode action(JsonNode program, String id) {
        for (JsonNode action : program.path("actions")) {
            if (id.equals(action.path("id").asText())) return action;
        }
        throw new AssertionError("프로그램 기능 없음: " + id);
    }

    private void save(String path, String json) throws Exception {
        mvc.perform(post("/api/admin" + path).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private void grant(String path, boolean granted) throws Exception {
        mvc.perform(put("/api/admin/authorities/AUTH_VIEWER" + path).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"granted\":" + granted + "}"))
                .andExpect(status().isOk());
    }
}
