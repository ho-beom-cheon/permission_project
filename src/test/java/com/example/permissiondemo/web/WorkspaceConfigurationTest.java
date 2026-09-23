package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorizationCatalog.*;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.project.ProjectService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 메뉴 위치와 관계없는 영역 분리, 실행 화면의 추가 권한, 관리 설정 변경의 실패 경로를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceConfigurationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthorizationCatalog catalog;
    @Autowired CommonCodeService codes;
    @Autowired ProjectService projects;

    @Test
    void rootAdminMenuIsNotExposedToPortalAndUserMenuNameDoesNotDecideArea() throws Exception {
        save("/menus/ROOT_TOOL", """
                {"name":"별도 관리","path":"#root-tool","sortOrder":90,"active":true,"displayed":true,"publicMenu":true,"area":"ADMIN"}
                """);
        save("/menus/SYSTEM_BUSINESS", """
                {"name":"사용자 시스템 업무","path":"#system-business","sortOrder":90,"active":true,"displayed":true,"publicMenu":true,"area":"USER"}
                """);
        assertThat(portal().path("services").toString()).contains("SYSTEM_BUSINESS").doesNotContain("ROOT_TOOL");
        var response = mvc.perform(get("/api/admin/bootstrap").with(user("admin"))).andExpect(status().isOk()).andReturn();
        JsonNode menus = mapper.readTree(response.getResponse().getContentAsByteArray()).path("data").path("context").path("menus");
        assertThat(menus.toString()).contains("ROOT_TOOL").doesNotContain("SYSTEM_BUSINESS");
    }

    @Test
    void mixedAreaHierarchyIsRejectedAndLegacyUpdatesPreserveArea() throws Exception {
        mvc.perform(post("/api/admin/menus/CROSS_AREA").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"parentId":"SYSTEM","name":"영역 불일치","path":"#cross-area","sortOrder":90,
                 "active":true,"displayed":true,"publicMenu":false,"area":"USER"}
                """)).andExpect(status().isConflict());
        save("/menus/ADMIN_CHILD", """
                {"parentId":"SYSTEM","name":"관리 메뉴","path":"#admin-child","sortOrder":90,
                 "active":true,"displayed":true,"publicMenu":false}
                """);
        assertThat(catalog.menus()).filteredOn(menu -> menu.id().equals("ADMIN_CHILD")).singleElement()
                .extracting(MenuDefinition::area).isEqualTo(MenuArea.ADMIN);
        assertThatThrownBy(() -> catalog.saveMenu(new MenuDefinition("SYSTEM", null, "시스템", null, 30,
                true, true, false, MenuArea.USER))).isInstanceOf(ApiException.class);
    }

    @Test
    void configuredScreenRequiresProgramActionAndDestinationAccess() throws Exception {
        catalog.saveMenu(new MenuDefinition("LINKED_WORK", null, "연결 업무", "#linked-work", 90, true, true, false, MenuArea.USER));
        catalog.setMenuGrant("AUTH_VIEWER", "LINKED_WORK", true);
        save("/programs/LINKED_WORK", """
                {"name":"연결 프로그램","description":"기존 화면 연결","active":true,"screen":"CONTENT_DEMO"}
                """);
        var action = new ProgramActionDefinition("LINKED_WORK", "LINKED_WORK", "OPEN", "업무 실행", null, 10, true);
        catalog.saveAction(action);
        assertThat(linkedProgram().path("launchable").asBoolean()).isFalse();
        catalog.setActionGrant("AUTH_VIEWER", action.key(), true);
        assertThat(linkedProgram().path("launchable").asBoolean()).isTrue();
        assertThat(linkedProgram().path("launchPath").asText()).isEqualTo("#content");
        var readContent = new ProgramActionKey("CONTENT_LIST", "CONTENT", "CONTENT_READ");
        catalog.setActionGrant("AUTH_VIEWER", readContent, false);
        assertThat(linkedProgram().path("launchable").asBoolean()).isFalse();
        catalog.setActionGrant("AUTH_VIEWER", readContent, true);
        catalog.setMenuGrant("AUTH_VIEWER", "CONTENT_LIST", false);
        assertThat(linkedProgram().path("launchable").asBoolean()).isFalse();
        mvc.perform(get("/api/content/preview").with(user("viewer"))).andExpect(status().isForbidden());
        catalog.setMenuGrant("AUTH_VIEWER", "CONTENT_LIST", true);
        save("/programs/LINKED_WORK", """
                {"name":"중지 프로그램","active":false,"screen":"CONTENT_DEMO"}
                """);
        assertThat(linkedProgram().path("launchable").asBoolean()).isFalse();
        mvc.perform(post("/api/admin/programs/UNSAFE").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"임의 화면 금지","active":true,"screen":"https://example.com"}
                """)).andExpect(status().isBadRequest());
    }

    @Test
    void inactiveStatusesBlockSaveAndPublishEvenThroughDirectApi() throws Exception {
        codes.deactivateItem("ARTICLE_STATUS", "PUBLISHED");
        mvc.perform(post("/api/content/publish").with(user("admin")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/content/save").with(user("admin")).with(csrf())).andExpect(status().isOk());
        codes.deactivateItem("ARTICLE_STATUS", "DRAFT");
        assertThat(codes.findActiveItems("ARTICLE_STATUS")).isEmpty();
        mvc.perform(post("/api/content/save").with(user("admin")).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void projectApiRejectsPartialSaveAndArchiveBypass() throws Exception {
        var original = projects.getProject("PRJ_ACCESS_RENEWAL").project();
        var body = mapper.valueToTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("status", "ON_HOLD").put("statusChangeReason", "");
        mvc.perform(post("/api/admin/projects/PRJ_ACCESS_RENEWAL").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        assertThat(projects.getProject(original.id()).project()).isEqualTo(original);
        var archived = projects.archiveProject(original.id(), original.version(), "admin", "보관 검증");
        body = mapper.valueToTree(archived);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("active", true);
        mvc.perform(post("/api/admin/projects/PRJ_ACCESS_RENEWAL").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        assertThat(projects.getProject(original.id()).project()).isEqualTo(archived);
        mvc.perform(post("/api/admin/projects/PRJ_ACCESS_RENEWAL/restore").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("version", archived.version(), "reason", "정상 복원"))))
                .andExpect(status().isOk());
        assertThat(projects.getProject(original.id()).project().active()).isTrue();
        assertThat(projects.getProject(original.id()).project().version()).isEqualTo(archived.version() + 1);
    }

    private JsonNode linkedProgram() throws Exception {
        for (var service : portal().path("services")) {
            if (service.path("id").asText().equals("LINKED_WORK")) return service.path("programs").get(0);
        }
        throw new AssertionError("연결 프로그램 없음");
    }
    private JsonNode portal() throws Exception {
        return mapper.readTree(mvc.perform(get("/api/portal/bootstrap").with(user("viewer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data").path("workspace");
    }
    private void save(String path, String json) throws Exception {
        mvc.perform(post("/api/admin" + path).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }
}
