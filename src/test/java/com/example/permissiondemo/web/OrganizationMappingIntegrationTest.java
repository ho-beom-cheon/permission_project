package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.OrganizationMappingService.WriteMapping;
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

@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OrganizationMappingIntegrationTest {
    private static final String URL = "/api/admin/organization-mappings";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DirectoryService directory;
    @Autowired AuthorizationCatalog catalog;
    private String key;

    @BeforeEach
    void createLocalOrganizations() throws Exception {
        key = UUID.randomUUID().toString();
        for (String office : List.of("MAPPING_A", "MAPPING_B")) {
            save("/api/admin/directory/offices", Map.of("code", office, "name", office, "active", true), 200);
        }
        save("/api/admin/directory/departments", Map.of("code", "TEAM", "officeCode", "MAPPING_A", "name", "합성 부서", "active", true), 200);
    }

    @Test
    void compositeSsoIdentityAllowsMultipleMappingsToOneLocalDepartment() throws Exception {
        save(URL, mapping(key, "SSO_TEAM", "MAPPING_A", "TEAM", true, 0), 200);
        save(URL, mapping(key + "B", "SSO_TEAM", "MAPPING_A", "TEAM", true, 0), 200);
        save(URL, mapping(key, "SSO_TEAM_2", "MAPPING_A", "TEAM", true, 0), 200);
        assertThat(find(key, "SSO_TEAM").path("departmentCode").asText()).isEqualTo("TEAM");
        assertThat(find(key + "B", "SSO_TEAM").path("officeCode").asText()).isEqualTo("MAPPING_A");
        assertThat(find(key, "SSO_TEAM_2").path("version").asLong()).isEqualTo(1);
    }

    @Test
    void supportsUnmappedAndOfficeOnlyRows() throws Exception {
        JsonNode unmapped = save(URL, mapping(key, "UNKNOWN", null, null, false, 0), 200);
        assertThat(unmapped.path("officeCode").asText()).isEmpty();
        assertThat(unmapped.path("departmentCode").asText()).isEmpty();
        assertThat(unmapped.path("active").asBoolean()).isFalse();
        JsonNode officeOnly = save(URL, mapping(key, "OFFICE_ONLY", "MAPPING_A", "", true, 0), 200);
        assertThat(officeOnly.path("officeCode").asText()).isEqualTo("MAPPING_A");
        assertThat(officeOnly.path("departmentCode").asText()).isEmpty();
    }

    @Test
    void rejectsMissingKeysAndInvalidLocalRelationshipsWithoutSaving() throws Exception {
        save(URL, mapping("", "TEAM", "", "", true, 0), 400);
        save(URL, mapping(key, "", "", "", true, 0), 400);
        save(URL, mapping(key, "TEAM", "MISSING", "", true, 0), 409);
        save(URL, mapping(key, "TEAM", "MAPPING_B", "TEAM", true, 0), 409);
        save(URL, mapping(key, "TEAM", "", "TEAM", true, 0), 409);
        assertThat(find(key, "TEAM").isMissingNode()).isTrue();
    }

    @Test
    void updatesRequireLatestVersionAndDoNotChangeUserOrganizationsOrAuthorities() throws Exception {
        JsonNode oldDirectory = json.valueToTree(directory.view());
        JsonNode oldAuthorization = json.valueToTree(catalog.snapshotState());
        save(URL, mapping(key, "TEAM", "MAPPING_A", "TEAM", true, 0), 200);
        JsonNode updated = save(URL, mapping(key, "TEAM", "MAPPING_B", "", false, 1), 200);
        assertThat(updated.path("version").asLong()).isEqualTo(2);
        save(URL, mapping(key, "TEAM", "MAPPING_A", "TEAM", true, 1), 409);
        assertThat(find(key, "TEAM")).isEqualTo(updated);
        assertThat(json.<JsonNode>valueToTree(directory.view())).isEqualTo(oldDirectory);
        assertThat(json.<JsonNode>valueToTree(catalog.snapshotState())).isEqualTo(oldAuthorization);
    }

    @Test
    void enforcesReadWritePermissionsAndCsrf() throws Exception {
        mvc.perform(get(URL).with(user("viewer"))).andExpect(status().isForbidden());
        byte[] body = json.writeValueAsBytes(mapping(key, "TEAM", "", "", true, 0));
        mvc.perform(post(URL).with(user("viewer")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).with(user("admin")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(find(key, "TEAM").isMissingNode()).isTrue();
    }

    @Test
    void emptyOrMissingStoredStateDoesNotInferMappings() throws Exception {
        var isolated = new OrganizationMappingService(mock(DirectoryService.class), mock(AuditEventService.class));
        assertThat(isolated.list()).isEmpty();
        isolated.restoreState(json.readValue("{}", OrganizationMappingService.StoredState.class));
        assertThat(isolated.list()).isEmpty();
        isolated.restoreState(new OrganizationMappingService.StoredState(List.of()));
        assertThat(isolated.list()).isEmpty();
        isolated.restoreState(null);
        assertThat(isolated.list()).isEmpty();
    }

    private WriteMapping mapping(String office, String department, String localOffice, String localDepartment, boolean active, long version) {
        return new WriteMapping(office, department, "합성 SSO사업소", "합성 SSO부서", localOffice, localDepartment, active, version);
    }

    private JsonNode save(String url, Object body, int expectedStatus) throws Exception {
        var response = mvc.perform(post(url).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().is(expectedStatus)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private JsonNode find(String office, String department) throws Exception {
        var response = mvc.perform(get(URL).with(user("admin"))).andExpect(status().isOk()).andReturn().getResponse();
        for (JsonNode row : json.readTree(response.getContentAsByteArray()).path("data")) {
            if (row.path("ssoOfficeCode").asText().equals(office) && row.path("ssoDepartmentCode").asText().equals(department)) return row;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
