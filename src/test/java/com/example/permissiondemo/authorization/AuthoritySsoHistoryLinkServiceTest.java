package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AuthoritySsoHistoryLinkServiceTest {
    private AuthorizationCatalog catalog;
    private OrganizationMappingService mappings;
    private CommonCodeService codes;
    private AuthoritySsoHistoryLinkService links;
    private UserSsoOrganizationService users;
    private final List<AuthorityAssignment> assignments = new ArrayList<>();
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        catalog = mock(AuthorizationCatalog.class);
        mappings = mock(OrganizationMappingService.class);
        codes = mock(CommonCodeService.class);
        var directory = mock(DirectoryService.class);
        var current = mock(CurrentUserContext.class);
        var audit = mock(AuditEventService.class);
        var provider = (ObjectProvider<UserSsoOrganizationService>) mock(ObjectProvider.class);
        when(catalog.assignmentsFor("user")).thenReturn(assignments);
        when(catalog.findAuthority(anyString())).thenAnswer(invocation -> Optional.of(new AuthorizationCatalog.AuthorityDefinition(invocation.getArgument(0), "합성 권한", false)));
        when(mappings.list()).thenReturn(List.of(mapping("SSO_A", "200", true), mapping("SSO_B", "OTHER", true)));
        when(current.require()).thenReturn(new CurrentUserContext.CurrentUser("user", "UNCHANGED_ORG", "", ""));
        var person = new DirectoryService.Person("user", "합성 사용자", "LOCAL_ORG", "", "", "", "", "", "", true, Set.of());
        when(directory.person("user")).thenReturn(person);
        when(directory.view()).thenReturn(new DirectoryService.DirectoryView(List.of(), List.of(), List.of(), List.of(person), List.of()));
        links = new AuthoritySsoHistoryLinkService(catalog, mappings, provider, audit);
        users = new UserSsoOrganizationService(directory, mappings, codes, current, audit, catalog, links);
        when(provider.getObject()).thenReturn(users);
        users.restoreState(new UserSsoOrganizationService.StoredState(List.of(new UserSsoOrganizationService.Affiliation("user", "SSO_A", "TEAM", 1))));
    }

    @Test
    void saveRequiresExactDirectAssignmentMappingTimeAndLatestVersion() {
        var assignment = assignment("AUTH_A", AssignmentStatus.APPROVED, 1);
        assertThatThrownBy(() -> links.save(write(assignment, "SSO_A", EARLIER, 0))).isInstanceOf(ApiException.class);
        assignments.add(assignment);
        assertThatThrownBy(() -> links.save(write(assignment, "UNKNOWN", EARLIER, 0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> links.save(write(assignment, "SSO_A", null, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThat(links.save(write(assignment, "SSO_A", EARLIER, 0)).version()).isEqualTo(1);
        assertThatThrownBy(() -> links.save(write(assignment, "SSO_B", LATER, 0))).isInstanceOf(ApiException.class);
        assertThat(links.save(write(assignment, "SSO_B", LATER, 1)).version()).isEqualTo(2);
        verify(catalog, never()).saveUserProfile(anyString(), anyString(), anyBoolean());
        assertThat(assignments).containsExactly(assignment);
    }

    @Test
    void latestTimestampThenSequenceIsChosenBeforeFilteringStatusAndIgnoresExpiryOrMasterActive() {
        var approved = assignment("AUTH_A", AssignmentStatus.APPROVED, 2);
        var rejected = assignment("AUTH_A", AssignmentStatus.REJECTED, 3);
        assignments.addAll(List.of(approved, rejected));
        links.save(write(approved, "SSO_A", LATER, 0));
        links.save(write(rejected, "SSO_A", EARLIER, 0));
        assertThat(users.colleagues(null, null, null, "AUTH_A", AssignmentStatus.APPROVED, "AUTH_").people()).hasSize(1);
        assertThat(users.colleagues(null, null, null, null, AssignmentStatus.REJECTED, null).people()).isEmpty();
        links.save(write(rejected, "SSO_A", LATER, 1));
        assertThat(users.colleagues(null, null, null, null, AssignmentStatus.APPROVED, null).people()).isEmpty();
        assertThat(users.colleagues(null, null, null, "AUTH_A", AssignmentStatus.REJECTED, "AUTH").people()).hasSize(1);
        assertThat(users.colleagues(null, null, null).people()).hasSize(1);
        assertThat(users.colleagues(null, null, null, "AUTH", null, null).people()).isEmpty();
        assertThat(users.colleagues(null, null, null, null, null, "OTHER").people()).isEmpty();
    }

    @Test
    void excludesUnlinkedDifferentSsoAndMissingAuthorityMaster() {
        var assignment = assignment("AUTH_A", AssignmentStatus.APPROVED, 1);
        assignments.add(assignment);
        assertThat(users.colleagues(null, null, null).people()).isEmpty();
        links.save(write(assignment, "SSO_B", EARLIER, 0));
        assertThat(users.colleagues(null, null, null).people()).isEmpty();
        links.save(write(assignment, "SSO_A", EARLIER, 1));
        when(catalog.findAuthority("AUTH_A")).thenReturn(Optional.empty());
        assertThat(users.colleagues(null, null, null).people()).isEmpty();
    }

    @Test
    void capturePreservesPredecessorSsoAfterUserMovesAndDoesNotGuessMissingPredecessor() {
        var original = assignment("AUTH_A", AssignmentStatus.PENDING, 1);
        var approved = assignment("AUTH_A", AssignmentStatus.APPROVED, 2);
        var unknown = assignment("AUTH_B", AssignmentStatus.APPROVED, 5);
        assignments.addAll(List.of(original, approved, unknown));
        links.capture(original, EARLIER, null);
        users.save(new UserSsoOrganizationService.WriteAffiliation("user", "SSO_B", "TEAM", 1));
        when(mappings.list()).thenReturn(List.of(mapping("SSO_A", "200", false), mapping("SSO_B", "OTHER", true)));
        links.capture(approved, LATER, 1L);
        links.capture(unknown, LATER, 4L);
        assertThat(links.list()).hasSize(2).allMatch(link -> link.ssoOfficeCode().equals("SSO_A"));
        links.capture(approved, Instant.parse("2026-02-01T00:00:00Z"), null);
        assertThat(links.list().get(0).registeredAt()).isEqualTo(LATER);
        users.restoreState(null);
        links.capture(unknown, LATER, null);
        assertThat(links.list()).hasSize(2);
    }

    @Test
    void delegatedHistoryCannotBeLinkedOrCapturedAndEmptyLegacyStateStaysEmpty() {
        var delegated = new AuthorityAssignment("user", "ORG", "AUTH_A", AssignmentType.DELEGATED, AssignmentStatus.APPROVED,
                LocalDate.of(2000,1,1), LocalDate.of(2001,1,1), "owner", 1);
        assignments.add(delegated);
        assertThatThrownBy(() -> links.save(write(delegated, "SSO_A", EARLIER, 0))).isInstanceOf(IllegalArgumentException.class);
        links.capture(delegated, EARLIER, null);
        assertThat(links.list()).isEmpty();
        links.restoreState(new AuthoritySsoHistoryLinkService.StoredState(null));
        links.restoreState(null);
        assertThat(links.list()).isEmpty();
    }

    @Test
    void office100IsConvertedBeforeHeadquartersOrBranchScopeCheck() {
        var assignment = assignment("AUTH_A", AssignmentStatus.APPROVED, 1);
        assignments.add(assignment); links.save(write(assignment, "SSO_A", EARLIER, 0));
        assertThat(users.colleagues(null, "100", null).officeCode()).isEqualTo("200");
        when(mappings.list()).thenReturn(List.of(mapping("SSO_A", "100", true)));
        assertThatThrownBy(() -> users.colleagues(null, "100", null)).isInstanceOf(ApiException.class);
        when(codes.groupSummary()).thenReturn(Map.of("CM087", 1));
        when(codes.findItems("CM087", false, "BONBU")).thenReturn(List.of(new CommonCodeService.CommonCodeItem("HQ", "SSO_A", "BONBU", 0, true, LocalDate.of(2000,1,1), LocalDate.of(2099,1,1))));
        assertThat(users.colleagues(null, "100", null).officeCode()).isEqualTo("200");
    }

    private OrganizationMappingService.Mapping mapping(String ssoOffice, String localOffice, boolean active) {
        return new OrganizationMappingService.Mapping(ssoOffice, "TEAM", "SSO사업소", "SSO부서", localOffice, "", active, 1);
    }
    private AuthorityAssignment assignment(String authority, AssignmentStatus status, long sequence) {
        return new AuthorityAssignment("user", "ORG", authority, AssignmentType.DIRECT, status, LocalDate.of(2000,1,1), LocalDate.of(2001,1,1), null, sequence);
    }
    private AuthoritySsoHistoryLinkService.WriteLink write(AuthorityAssignment assignment, String ssoOffice, Instant time, long version) {
        return new AuthoritySsoHistoryLinkService.WriteLink(assignment.username(), assignment.organizationId(), assignment.authorityId(), assignment.type(), assignment.sequence(), ssoOffice, "TEAM", time, version);
    }
}
