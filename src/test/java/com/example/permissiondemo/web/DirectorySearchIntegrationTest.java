package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.example.permissiondemo.PermissionDemoApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 원본 담당자 조회의 사용자명·사업소·사용 여부 조건을 합성 자료로 검증한다. */
@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DirectorySearchIntegrationTest {
    private static final String URL = "/api/admin/directory/people";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void createSyntheticPeople() throws Exception {
        for (String office : List.of("SEARCH_A", "SEARCH_B")) {
            save("/api/admin/directory/offices", Map.of("code", office, "name", office, "active", true));
        }
        person("search_a", "이관Alpha 하나", "SEARCH_A", true);
        person("search_b", "이관Alpha 둘", "SEARCH_A", false);
        person("search_c", "이관Alpha 셋", "SEARCH_B", true);
        person("search_d", "이관alpha 넷", "SEARCH_A", true);
    }

    @Test
    void nameMatchesDisplayNamePartiallyAndCaseSensitively() throws Exception {
        assertThat(usernames(search(get(URL).param("name", "관Alpha"))))
                .containsExactly("search_a", "search_b", "search_c");
        assertThat(usernames(search(get(URL).param("name", "관alpha"))))
                .containsExactly("search_d");
        assertThat(search(get(URL).param("name", "search_a")).isEmpty()).isTrue();
    }

    @Test
    void namePreservesSqlLikeWildcardsButTreatsRegexSymbolsLiterally() throws Exception {
        assertThat(usernames(search(get(URL).param("name", "이관A%둘")))).containsExactly("search_b");
        assertThat(usernames(search(get(URL).param("name", "Alpha _"))))
                .containsExactly("search_a", "search_b", "search_c");
        person("search_regex", "이관A.[x]+(test)", "SEARCH_B", true);
        assertThat(usernames(search(get(URL).param("name", ".[x]+(")))).containsExactly("search_regex");
        assertThat(search(get(URL).param("name", "Alpha.*")).isEmpty()).isTrue();
    }

    @Test
    void officeMatchesExactlyAndActiveCanSelectDisabledPeople() throws Exception {
        assertThat(usernames(search(get(URL).param("officeCode", "SEARCH_A"))))
                .containsExactly("search_a", "search_b", "search_d");
        assertThat(search(get(URL).param("officeCode", "SEARCH")).isEmpty()).isTrue();
        JsonNode inactive = search(get(URL).param("active", "false"));
        assertThat(usernames(inactive)).contains("search_b").doesNotContain("search_a", "search_c", "search_d");
        inactive.forEach(person -> assertThat(person.path("active").asBoolean()).isFalse());
        JsonNode active = search(get(URL).param("active", "true"));
        assertThat(usernames(active)).contains("search_a", "search_c", "search_d").doesNotContain("search_b");
        active.forEach(person -> assertThat(person.path("active").asBoolean()).isTrue());
    }

    @Test
    void filtersCombineWithAndAndDoNotChangeDirectorySnapshot() throws Exception {
        JsonNode before = search(get("/api/admin/directory")).path("people");
        assertThat(usernames(search(get(URL).param("name", "Alpha").param("officeCode", "SEARCH_A").param("active", "false"))))
                .containsExactly("search_b");
        assertThat(search(get(URL).param("name", "alpha").param("officeCode", "SEARCH_B").param("active", "true")).isEmpty()).isTrue();
        assertThat(search(get("/api/admin/directory")).path("people")).isEqualTo(before);
    }

    @Test
    void omittedAndEmptyConditionsReturnAllPeople() throws Exception {
        JsonNode all = search(get("/api/admin/directory")).path("people");
        assertThat(search(get(URL))).isEqualTo(all);
        assertThat(search(get(URL).param("name", "").param("officeCode", "").param("active", ""))).isEqualTo(all);
        assertThat(search(get(URL).param("name", "존재하지않는검색이름")).isEmpty()).isTrue();
    }

    @Test
    void rejectsInvalidActiveValueAndUserWithoutReadPermission() throws Exception {
        mvc.perform(get(URL).param("active", "invalid").with(user("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get(URL).with(user("viewer"))).andExpect(status().isForbidden());
    }

    private void person(String username, String name, String officeCode, boolean active) throws Exception {
        save(URL, Map.of("person", Map.of("username", username, "name", name, "officeCode", officeCode, "active", active)));
    }

    private void save(String url, Object body) throws Exception {
        mvc.perform(post(url).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().isOk());
    }

    private JsonNode search(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request.with(user("admin"))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private List<String> usernames(JsonNode people) {
        List<String> result = new ArrayList<>();
        people.forEach(person -> result.add(person.path("username").asText()));
        return result;
    }
}
