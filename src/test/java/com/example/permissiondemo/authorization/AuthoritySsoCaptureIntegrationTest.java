package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorityRequestService.Decision;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(classes=PermissionDemoApplication.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser("admin")
class AuthoritySsoCaptureIntegrationTest {
    @Autowired DirectoryService directory;
    @Autowired OrganizationMappingService mappings;
    @Autowired UserSsoOrganizationService affiliations;
    @Autowired AuthoritySsoHistoryLinkService links;
    @Autowired AuthorityRequestService requests;
    @Autowired AdminService admin;
    String username, ssoA, ssoB;

    @BeforeEach void fixture() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        username = "capture_" + key; ssoA = "SSOA_" + key; ssoB = "SSOB_" + key;
        person("HQ");
        for (String office : List.of(ssoA, ssoB)) mappings.save(new OrganizationMappingService.WriteMapping(
                office, "DEP", "합성 사업소", "합성 부서", "HQ", "", true, 0));
    }

    void person(String office) {
        directory.savePerson(new DirectoryService.Person(username, username, office, "", "", "", "", "", "", true, Set.of()));
    }

    void affiliate(String office, long version) {
        affiliations.save(new UserSsoOrganizationService.WriteAffiliation(username, office, "DEP", version));
    }

    List<AuthoritySsoHistoryLinkService.Link> ownLinks() {
        return links.list().stream().filter(link -> link.username().equals(username)).toList();
    }

    @Test void rejectionAfterOrganizationMoveKeepsOriginalPendingAffiliation() {
        affiliate(ssoA, 0);
        var pending = requests.submit(username, RequestKind.GRANT, Set.of("AUTH_VIEWER"), null, null, "합성 신청");
        assertThat(ownLinks()).hasSize(1);
        affiliate(ssoB, 1); person("BRANCH_B");
        requests.review("admin", List.of(pending.id()), Decision.REJECT, "이동 후 반려");
        assertThat(ownLinks()).hasSize(2).allSatisfy(link -> {
            assertThat(link.ssoOfficeCode()).isEqualTo(ssoA);
            assertThat(link.organizationId()).isEqualTo("HQ");
        });
    }

    @Test void approvalAndLaterRevocationInheritLinkedHistoryInsteadOfCurrentAffiliation() {
        affiliate(ssoA, 0);
        var pending = requests.submit(username, RequestKind.GRANT, Set.of("AUTH_VIEWER"), null, null, "합성 신청");
        requests.review("admin", List.of(pending.id()), Decision.APPROVE, "승인");
        affiliate(ssoB, 1);
        admin.revokeAuthority(username, "AUTH_VIEWER", AssignmentType.DIRECT, "회수 사유");
        assertThat(ownLinks()).hasSize(3).allSatisfy(link -> assertThat(link.ssoOfficeCode()).isEqualTo(ssoA));
    }

    @Test void lateAffiliationDoesNotBackfillPendingButNewDirectGrantCapturesIt() {
        var pending = requests.submit(username, RequestKind.GRANT, Set.of("AUTH_VIEWER"), null, null, "소속 전 신청");
        assertThat(ownLinks()).isEmpty();
        affiliate(ssoA, 0);
        requests.review("admin", List.of(pending.id()), Decision.APPROVE, "이전 신청 승인");
        admin.revokeAuthority(username, "AUTH_VIEWER", AssignmentType.DIRECT, "회수 사유");
        assertThat(ownLinks()).isEmpty();
        admin.assignAuthority(username, "AUTH_CONTENT_MANAGER", AssignmentType.DIRECT, LocalDate.now(), LocalDate.now().plusDays(3), null);
        assertThat(ownLinks()).hasSize(1).allSatisfy(link -> {
            assertThat(link.authorityId()).isEqualTo("AUTH_CONTENT_MANAGER");
            assertThat(link.ssoOfficeCode()).isEqualTo(ssoA);
        });
    }
}
