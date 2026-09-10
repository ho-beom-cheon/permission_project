package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorityRequestService;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.LocalAccountService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 디렉터리 bootstrap API의 접근 통제, 원자성, 기존 보안 상태 보존을 검증한다. */
@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DirectoryBootstrapImportIntegrationTest {
    private static final String URL = "/api/admin/directory/import";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DirectoryService directory;
    @Autowired LocalAccountService accounts;
    @Autowired AuthorizationCatalog catalog;
    @Autowired AuthorityRequestService requests;
    @Autowired OrganizationMappingService mappings;
    @Autowired UserSsoOrganizationService affiliations;
    @Autowired CommonCodeService codes;
    @Autowired AuditEventService audit;

    @Test
    void requiresAuthorityUpdatePermission() throws Exception {
        var before = directory.snapshotState();
        mvc.perform(post(URL).with(user("viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(emptyRequest()))
                .andExpect(status().isForbidden());
        assertThat(directory.snapshotState()).isEqualTo(before);
    }

    @Test
    void requiresCsrfToken() throws Exception {
        var before = directory.snapshotState();
        mvc.perform(post(URL).with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(emptyRequest()))
                .andExpect(status().isForbidden());
        assertThat(directory.snapshotState()).isEqualTo(before);
    }

    @Test
    void rejectsUnmappedPersonBeforeMutatingAnyEarlierSection() throws Exception {
        var before = directory.snapshotState();
        Map<String, Object> request = request();
        request.put("offices", List.of(Map.of(
                "code", "BOOT_ATOMIC", "name", "원자성 검증 사업소", "useFlag", "Y")));
        request.put("people", List.of(Map.of(
                "username", "bootstrap_atomic", "name", "원자성 검증 사용자",
                "officeCode", "BOOT_ATOMIC", "departmentCode", "",
                "ssoOfficeCode", "UNMAPPED_OFFICE", "ssoDepartmentCode", "UNMAPPED_DEPT",
                "rankCode", "", "useFlag", "Y")));

        mvc.perform(post(URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isConflict());

        assertThat(directory.snapshotState()).isEqualTo(before);
        assertThat(directory.view().offices()).noneMatch(office -> office.code().equals("BOOT_ATOMIC"));
    }

    @Test
    @WithMockUser(username = "admin")
    void updatesDirectoryPersonWithoutReplacingPasswordAuthoritiesOrPendingRequests() throws Exception {
        requests.submit("revoked", RequestKind.GRANT, Set.of(AuthorizationCatalog.AUTH_VIEWER),
                null, null, "bootstrap 보존 검증");
        LocalAccountService.StoredState passwordsBefore = (LocalAccountService.StoredState) accounts.snapshotState();
        AuthorizationCatalog.StoredState authorizationBefore = (AuthorizationCatalog.StoredState) catalog.snapshotState();
        AuthorityRequestService.StoredState requestsBefore = (AuthorityRequestService.StoredState) requests.snapshotState();

        Map<String, Object> request = request();
        request.put("organizationMappings", List.of(Map.of(
                "ssoOfficeCode", "SSO_HQ", "ssoDepartmentCode", "SSO_ADMIN",
                "ssoOfficeName", "본부", "ssoDepartmentName", "관리부서",
                "officeCode", "HQ", "departmentCode", "", "useFlag", "Y")));
        request.put("people", List.of(Map.ofEntries(
                Map.entry("username", "admin"), Map.entry("name", "관리자 이관명"),
                Map.entry("officeCode", "HQ"), Map.entry("departmentCode", ""),
                Map.entry("ssoOfficeCode", "SSO_HQ"), Map.entry("ssoDepartmentCode", "SSO_ADMIN"),
                Map.entry("rankCode", ""), Map.entry("telephone", "0212345678"),
                Map.entry("mobile", ""), Map.entry("email", "admin@example.test"),
                Map.entry("jobDescription", "이관 검증"), Map.entry("useFlag", "Y"))));

        mvc.perform(post(URL).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk());

        assertThat(directory.person("admin").name()).isEqualTo("관리자 이관명");
        assertThat(accounts.snapshotState()).isEqualTo(passwordsBefore);
        AuthorizationCatalog.StoredState authorizationAfter = (AuthorizationCatalog.StoredState) catalog.snapshotState();
        assertThat(authorizationAfter.assignments()).isEqualTo(authorizationBefore.assignments());
        assertThat(authorizationAfter.menuGrants()).isEqualTo(authorizationBefore.menuGrants());
        assertThat(authorizationAfter.actionGrants()).isEqualTo(authorizationBefore.actionGrants());
        assertThat(requests.snapshotState()).isEqualTo(requestsBefore);
    }

    @Test
    void importsCompleteGraphAndSecondRunIsStateAndAuditIdempotent() throws Exception {
        Map<String, Object> request = request();
        request.put("offices", List.of(
                Map.of("code", "002", "name", "본부", "abbreviation", "본부", "address", "", "telephone", "02", "useFlag", "Y"),
                Map.of("code", "101", "name", "동부사업소", "abbreviation", "동부", "address", "", "telephone", "02", "useFlag", "Y")));
        request.put("departments", List.of(Map.of("code", "D101", "officeCode", "101", "name", "요금부", "description", "", "useFlag", "Y")));
        request.put("organizationMappings", List.of(Map.of(
                "ssoOfficeCode", "SSO_002", "ssoDepartmentCode", "SSO_D002", "ssoOfficeName", "본부", "ssoDepartmentName", "본부부서",
                "officeCode", "002", "departmentCode", "", "useFlag", "Y")));
        request.put("regions", List.of(Map.of("code", "1111010100", "officeCode", "101", "districtCode", "110", "districtName", "종로구", "name", "청운효자동", "useFlag", "Y")));
        request.put("rankGroup", Map.of("name", "직급", "description", "직급 코드", "useFlag", "Y"));
        request.put("rankCodes", List.of(Map.ofEntries(
                Map.entry("code", "R01"), Map.entry("name", "주무관"), Map.entry("description", ""), Map.entry("sortOrder", 10), Map.entry("useFlag", "Y"),
                Map.entry("screen1", "Y"), Map.entry("screen2", "Y"), Map.entry("screen3", "Y"), Map.entry("screen4", "Y"), Map.entry("screen5", "Y"),
                Map.entry("substituteName", ""), Map.entry("screenDescription", ""))));
        request.put("people", List.of(Map.ofEntries(
                Map.entry("username", "boot_hq"), Map.entry("name", "본부 이관 사용자"), Map.entry("officeCode", "002"), Map.entry("departmentCode", ""),
                Map.entry("ssoOfficeCode", " SSO_002 "), Map.entry("ssoDepartmentCode", " SSO_D002 "), Map.entry("rankCode", "R01"),
                Map.entry("telephone", "0212345678"), Map.entry("mobile", ""), Map.entry("email", "boot@example.test"),
                Map.entry("jobDescription", "지역 업무"), Map.entry("useFlag", "Y"))));
        request.put("userRegions", List.of(Map.of("username", "boot_hq", "regionCode", "1111010100")));

        mvc.perform(post(URL).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isOk());

        assertThat(directory.person("boot_hq").regionIds()).containsExactly("1111010100");
        assertThat(affiliations.find("boot_hq")).isPresent();
        assertThat(codes.findItems("CM008", false, null)).extracting(CommonCodeService.CommonCodeItem::code).contains("R01");
        Object directoryBefore = directory.snapshotState(), mappingBefore = mappings.snapshotState();
        Object affiliationBefore = affiliations.snapshotState(), codeBefore = codes.snapshotState(), auditBefore = audit.snapshotState();

        mvc.perform(post(URL).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isOk());

        assertThat(directory.snapshotState()).isEqualTo(directoryBefore);
        assertThat(mappings.snapshotState()).isEqualTo(mappingBefore);
        assertThat(affiliations.snapshotState()).isEqualTo(affiliationBefore);
        assertThat(codes.snapshotState()).isEqualTo(codeBefore);
        assertThat(audit.snapshotState()).isEqualTo(auditBefore);
    }

    @Test
    void preservesInactiveUserWithRetiredMappingAndRegion() throws Exception {
        Map<String, Object> request = request();
        request.put("offices", List.of(Map.of("code", "OLD", "name", "폐지 사업소", "useFlag", "N")));
        request.put("organizationMappings", List.of(Map.of(
                "ssoOfficeCode", "SSO_OLD", "ssoDepartmentCode", "SSO_D_OLD", "ssoOfficeName", "폐지", "ssoDepartmentName", "폐지부서",
                "officeCode", "", "departmentCode", "", "useFlag", "N")));
        request.put("regions", List.of(Map.of("code", "R_OLD", "officeCode", "OLD", "districtCode", "", "districtName", "", "name", "폐지 지역", "useFlag", "N")));
        request.put("people", List.of(Map.ofEntries(
                Map.entry("username", "retired_user"), Map.entry("name", "퇴직 사용자"), Map.entry("officeCode", "OLD"), Map.entry("departmentCode", ""),
                Map.entry("ssoOfficeCode", "SSO_OLD"), Map.entry("ssoDepartmentCode", "SSO_D_OLD"), Map.entry("rankCode", ""),
                Map.entry("telephone", ""), Map.entry("mobile", ""), Map.entry("email", ""), Map.entry("jobDescription", ""), Map.entry("useFlag", "N"))));
        request.put("userRegions", List.of(Map.of("username", "retired_user", "regionCode", "R_OLD")));

        mvc.perform(post(URL).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isOk());

        assertThat(directory.person("retired_user").active()).isFalse();
        assertThat(directory.person("retired_user").regionIds()).containsExactly("R_OLD");
        assertThat(affiliations.find("retired_user")).isPresent().get()
                .extracting(UserSsoOrganizationService.Affiliation::ssoOfficeCode).isEqualTo("SSO_OLD");
    }

    private String emptyRequest() throws Exception {
        return json.writeValueAsString(request());
    }

    private Map<String, Object> request() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("offices", List.of());
        request.put("departments", List.of());
        request.put("organizationMappings", List.of());
        request.put("regions", List.of());
        request.put("people", List.of());
        request.put("userRegions", List.of());
        request.put("rankGroup", null);
        request.put("rankCodes", List.of());
        return request;
    }
}
