package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser("admin")
class IndividualAuthorityRevocationIntegrationTest {
    @Autowired DirectoryService directory;
    @Autowired OrganizationMappingService mappings;
    @Autowired UserSsoOrganizationService affiliations;
    @Autowired SsoAuthorityScopeService scopes;
    @Autowired AuthorizationCatalog catalog;
    @Autowired AdminService admin;
    @Autowired AuthorityHistoryService history;
    @Autowired AuthorityRequestService requests;
    @Autowired MockMvc mvc;
    @Autowired Clock clock;

    String username;
    String office;
    String otherOffice;
    LocalDate today;

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        username = "individual_" + suffix;
        office = "OFF_" + suffix;
        otherOffice = "OTHER_" + suffix;
        today = LocalDate.now(clock);
        directory.savePerson(new DirectoryService.Person(
                username, username, "HQ", "", "", "", "", "", "", true, Set.of()));
        for (String code : List.of(office, otherOffice)) {
            mappings.save(new OrganizationMappingService.WriteMapping(
                    code, "DEP", code, "합성 부서", "HQ", "", true, 0));
        }
        affiliations.save(new UserSsoOrganizationService.WriteAffiliation(username, office, "DEP", 0));
        scopes.save(new SsoAuthorityScopeService.WriteMode(username, true, 0));
        admin.assignAuthority(username, "AUTH_VIEWER", AssignmentType.DIRECT,
                today.minusDays(10), today.plusYears(1), null);
    }

    @Test
    void individualRevokeRequiresReasonEndsTodayAndReasonCorrectionChangesOnlyReason() {
        int assignmentsBefore = catalog.assignmentsFor(username).size();
        var approved = catalog.assignmentsFor(username).get(assignmentsBefore - 1);

        var revoked = admin.revokeAuthority(username, "AUTH_VIEWER", AssignmentType.DIRECT, "최초 회수 사유");

        assertThat(revoked.validFrom()).isEqualTo(approved.validFrom());
        assertThat(revoked.validTo()).isEqualTo(today);
        assertThat(catalog.assignmentsFor(username)).hasSize(assignmentsBefore + 1);
        var original = history.all().stream()
                .filter(event -> event.username().equals(username))
                .filter(event -> event.assignmentSequence().equals(revoked.sequence()))
                .findFirst().orElseThrow();
        assertThat(original.reason()).isEqualTo("최초 회수 사유");

        int historyCount = history.all().size();
        int assignmentCount = catalog.assignmentsFor(username).size();
        var corrected = history.correctLatestReason(
                username, "AUTH_VIEWER", office, "DEP", "정정한 회수 사유");

        assertThat(corrected.reason()).isEqualTo("정정한 회수 사유");
        assertThat(corrected).usingRecursiveComparison().ignoringFields("reason").isEqualTo(original);
        assertThat(history.all()).hasSize(historyCount);
        assertThat(catalog.assignmentsFor(username)).hasSize(assignmentCount);
        assertThatThrownBy(() -> history.correctLatestReason(
                username, "AUTH_VIEWER", otherOffice, "DEP", "다른 범위"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void latestRejectedReasonIsCorrectedWithoutChangingProcessingMetadata() {
        var request = requests.submit(username, AuthorityRequestService.RequestKind.GRANT,
                Set.of("AUTH_CONTENT_MANAGER"), today, today.plusDays(30), "신청 사유");
        requests.review("admin", List.of(request.id()), AuthorityRequestService.Decision.REJECT, "최초 반려 사유");
        var original = history.all().stream()
                .filter(event -> event.username().equals(username))
                .filter(event -> event.authorityId().equals("AUTH_CONTENT_MANAGER"))
                .filter(event -> event.status() == AuthorityHistoryService.HistoryStatus.REJECTED)
                .findFirst().orElseThrow();
        int historyCount = history.all().size();
        int assignmentCount = catalog.assignmentsFor(username).size();

        var corrected = history.correctLatestReason(
                username, "AUTH_CONTENT_MANAGER", office, "DEP", "정정한 반려 사유");

        assertThat(corrected.reason()).isEqualTo("정정한 반려 사유");
        assertThat(corrected).usingRecursiveComparison().ignoringFields("reason").isEqualTo(original);
        assertThat(history.all()).hasSize(historyCount);
        assertThat(catalog.assignmentsFor(username)).hasSize(assignmentCount);
    }

    @Test
    void mutationEndpointsRequireReasonUpdatePermissionAndCsrf() throws Exception {
        String revokePath = "/api/admin/users/" + username + "/authorities/AUTH_VIEWER";
        mvc.perform(delete(revokePath).with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DIRECT\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        String correction = "{\"username\":\"" + username + "\",\"authorityId\":\"AUTH_VIEWER\","
                + "\"ssoOfficeCode\":\"" + office + "\",\"ssoDepartmentCode\":\"DEP\",\"reason\":\"정정\"}";
        mvc.perform(patch("/api/admin/authority-history/reason").with(user("viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/authority-history/reason").with(user("admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isForbidden());
    }
}
