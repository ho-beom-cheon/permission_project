package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorizationCatalog.ProgramActionKey;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.content.ContentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceContentApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ContentRepository repository;
    @Autowired AuthorizationCatalog catalog;
    @Autowired CommonCodeService codes;

    @Test
    void createReadUpdateAndSearchUseStoredDataAndServerAuthor() throws Exception {
        JsonNode created = create("한글 제목", "<script>alert(1)</script>\n본문", "DRAFT");
        assertThat(created.path("id").isTextual()).isTrue();
        assertThat(created.path("version").isTextual()).isTrue();
        assertThat(created.path("author").asText()).isEqualTo("admin");
        mvc.perform(get("/api/contents/" + created.path("id").asText()).with(user("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.body").value("<script>alert(1)</script>\n본문"));
        mvc.perform(put("/api/contents/" + created.path("id").asText()).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(write("수정 제목", "본문 변경", "DRAFT", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("2"));
        mvc.perform(get("/api/contents").param("query", "수정").param("size", "1").with(user("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("수정 제목"));
        mvc.perform(put("/api/contents/" + created.path("id").asText()).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(write("오래된 수정", "본문", "DRAFT", 1)))
                .andExpect(status().isConflict());
        assertThat(repository.findById(created.path("id").asLong()).orElseThrow().title()).isEqualTo("수정 제목");
    }
    @Test
    void authenticationPermissionsAndCsrfAreRequiredForRealEndpoints() throws Exception {
        mvc.perform(get("/api/contents")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/contents").with(user("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(write("제목", "본문", "DRAFT", 0))).andExpect(status().isForbidden());
        mvc.perform(post("/api/contents").with(user("viewer")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(write("제목", "본문", "DRAFT", 0))).andExpect(status().isForbidden());
        mvc.perform(post("/api/contents/publish").with(user("viewer")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}")).andExpect(status().isForbidden());
        catalog.setActionGrant("AUTH_VIEWER", new ProgramActionKey("CONTENT_LIST", "CONTENT", "CONTENT_READ"), false);
        mvc.perform(get("/api/contents").with(user("viewer"))).andExpect(status().isForbidden());
    }
    @Test
    void saveCannotBypassPublishPermissionOrModifyPublishedContentWithoutIt() throws Exception {
        var published = create("게시물", "본문", "PUBLISHED");
        catalog.setActionGrant("AUTH_SYSTEM_ADMIN", new ProgramActionKey("CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH"), false);
        mvc.perform(post("/api/contents").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(write("우회", "본문", "PUBLISHED", 0))).andExpect(status().isForbidden());
        mvc.perform(put("/api/contents/" + published.path("id").asText()).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(write("우회", "본문", "DRAFT", 1))).andExpect(status().isForbidden());
        assertThat(repository.findById(published.path("id").asLong()).orElseThrow().version()).isEqualTo(1);
        create("정상 초안", "본문", "DRAFT");
    }
    @Test
    void batchPublishValidatesEveryVersionBeforeChangingAnyRecord() throws Exception {
        var first = create("첫째", "본문", "DRAFT");
        var second = create("둘째", "본문", "DRAFT");
        var firstRef = Map.of("id", first.path("id").asText(), "version", "1");
        var secondRef = Map.of("id", second.path("id").asText(), "version", "99");
        publish(List.of(firstRef, secondRef), 409);
        assertThat(repository.findById(first.path("id").asLong()).orElseThrow().status()).isEqualTo("DRAFT");
        publish(List.of(firstRef, firstRef), 400);
        publish(List.of(firstRef, Map.of("id", second.path("id").asText(), "version", "1")), 200);
        assertThat(repository.findById(first.path("id").asLong()).orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(repository.findById(second.path("id").asLong()).orElseThrow().version()).isEqualTo(2);
    }
    @Test
    void inactiveStatusesAndInvalidInputLeaveRecordsUnchanged() throws Exception {
        var created = create("정상 제목", "본문", "DRAFT");
        codes.deactivateItem("ARTICLE_STATUS", "PUBLISHED");
        publish(List.of(Map.of("id", created.path("id").asText(), "version", "1")), 409);
        codes.deactivateItem("ARTICLE_STATUS", "DRAFT");
        mvc.perform(put("/api/contents/" + created.path("id").asText()).with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(write("실패", "본문", "DRAFT", 1))).andExpect(status().isConflict());
        mvc.perform(post("/api/contents").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(write(" ", "본문", "DRAFT", 0))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/contents").param("size", "101").with(user("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/contents").param("field", "TITLE DESC").with(user("admin"))).andExpect(status().isBadRequest());
        assertThat(repository.findById(created.path("id").asLong()).orElseThrow().version()).isEqualTo(1);
    }
    @Test
    void concurrentUpdatesUsingOneVersionHaveExactlyOneWinner() throws Exception {
        var created = create("경합", "본문", "DRAFT");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> update = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return mvc.perform(put("/api/contents/" + created.path("id").asText()).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(write("경합 수정", "본문", "DRAFT", 1)))
                        .andReturn().getResponse().getStatus();
            };
            var a = pool.submit(update); var b = pool.submit(update); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(repository.findById(created.path("id").asLong()).orElseThrow().version()).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }
    private JsonNode create(String title, String body, String state) throws Exception {
        return json.readTree(mvc.perform(post("/api/contents").with(user("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(write(title, body, state, 0)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    private String write(String title, String body, String state, long version) throws Exception {
        return json.writeValueAsString(Map.of("type", "업무", "title", title, "body", body, "status", state,
                "version", Long.toString(version), "author", "forged-user"));
    }
    private void publish(List<?> items, int expected) throws Exception {
        mvc.perform(post("/api/contents/publish").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", items)))).andExpect(status().is(expected));
    }
}
