package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

class SsoAuthorityScopeServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");
    private final SsoAuthorityScopeService.Scope a = new SsoAuthorityScopeService.Scope("SSO_A", "TEAM");
    private final SsoAuthorityScopeService.Scope b = new SsoAuthorityScopeService.Scope("SSO_B", "TEAM");
    private AuthorizationCatalog catalog;
    private OrganizationMappingService mappings;
    private UserSsoOrganizationService users;
    private AuthoritySsoHistoryLinkService links;
    private SsoAuthorityScopeService scopes;
    private EffectiveAuthorityService effective;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        catalog = new AuthorizationCatalog();
        var audit = mock(AuditEventService.class);
        var directory = new DirectoryService(catalog, audit);
        mappings = new OrganizationMappingService(directory, audit);
        var userProvider = (ObjectProvider<UserSsoOrganizationService>) mock(ObjectProvider.class);
        var scopeProvider = (ObjectProvider<SsoAuthorityScopeService>) mock(ObjectProvider.class);
        links = new AuthoritySsoHistoryLinkService(catalog, mappings, userProvider, audit);
        users = new UserSsoOrganizationService(directory, mappings, new CommonCodeService(audit, clock), mock(CurrentUserContext.class), audit, catalog, links);
        when(userProvider.getObject()).thenReturn(users);
        scopes = new SsoAuthorityScopeService(catalog, links, users, mappings, audit, clock);
        when(scopeProvider.getIfAvailable()).thenReturn(scopes);
        when(scopeProvider.getObject()).thenReturn(scopes);
        links.configureScopeInvalidation(scopeProvider);
        users.configureScopeInvalidation(scopeProvider);
        mappings.configureScopeInvalidation(scopeProvider);
        effective = new EffectiveAuthorityService(catalog, clock, scopeProvider);
        mappings.save(mapping("SSO_A", true, 0)); mappings.save(mapping("SSO_B", true, 0));
    }

    @Test
    void legacyModeDefaultsOffAndEnabledModeUsesOnlyLinkedDirectCurrentScope() {
        assertThat(scopes.isEnabled("viewer")).isFalse();
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly(AuthorizationCatalog.AUTH_VIEWER);
        affiliate("viewer", "SSO_A", 0);
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", true, 0));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
        AuthorityAssignment direct = direct("viewer", AuthorizationCatalog.AUTH_VIEWER);
        scopes.record(direct, a, time);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly(AuthorizationCatalog.AUTH_VIEWER);
        affiliate("viewer", "SSO_B", 1);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
        assertThat(catalog.findUser("viewer").orElseThrow().organizationId()).isEqualTo("HQ");
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", false, 1));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly(AuthorizationCatalog.AUTH_VIEWER);
        scopes.restoreState(null);
        assertThat(scopes.isEnabled("viewer")).isFalse();
    }

    @Test
    void enabledRecipientAndLegacyRecipientWithEnabledDelegatorDoNotInheritDelegation() {
        assertThat(effective.findEffectiveAuthorityIds("delegate")).contains(AuthorizationCatalog.AUTH_CONTENT_MANAGER);
        affiliate("manager", "SSO_A", 0);
        scopes.record(direct("manager", AuthorizationCatalog.AUTH_CONTENT_MANAGER), a, time);
        scopes.save(new SsoAuthorityScopeService.WriteMode("manager", true, 0));
        assertThat(effective.hasDirectAuthority("manager", AuthorizationCatalog.AUTH_CONTENT_MANAGER)).isTrue();
        assertThat(effective.findEffectiveAuthorityIds("delegate")).containsExactly(AuthorizationCatalog.AUTH_VIEWER);
        affiliate("delegate", "SSO_A", 0);
        scopes.record(direct("delegate", AuthorizationCatalog.AUTH_VIEWER), a, time);
        scopes.save(new SsoAuthorityScopeService.WriteMode("delegate", true, 0));
        assertThat(effective.findEffectiveAuthorityIds("delegate")).containsExactly(AuthorizationCatalog.AUTH_VIEWER);
    }

    @Test
    void latestStatusIsSelectedBeforeExpiryAndMasterActivationChecksWithoutLegacyFallback() {
        affiliate("viewer", "SSO_A", 0);
        AuthorityAssignment approved = direct("viewer", AuthorizationCatalog.AUTH_VIEWER);
        scopes.record(approved, a, time);
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", true, 0));
        var pending = catalog.createScopedAssignment("viewer", "HQ", AuthorizationCatalog.AUTH_VIEWER, AssignmentStatus.PENDING,
                LocalDate.of(2020,1,1), LocalDate.of(2099,1,1));
        var rejected = catalog.processScopedAssignment(pending, AssignmentStatus.REJECTED, LocalDate.of(2020,1,1), LocalDate.of(2099,1,1));
        scopes.record(rejected, a, time.plusSeconds(1));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
        assertThatThrownBy(() -> scopes.requireLatest(approved, a)).isInstanceOf(ApiException.class);
        var expired = catalog.createScopedAssignment("viewer", "OTHER", AuthorizationCatalog.AUTH_VIEWER, AssignmentStatus.APPROVED,
                LocalDate.of(2020,1,1), LocalDate.of(2021,1,1));
        scopes.record(expired, a, time.plusSeconds(2));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
        var valid = catalog.createScopedAssignment("viewer", "OTHER", AuthorizationCatalog.AUTH_VIEWER, AssignmentStatus.APPROVED,
                LocalDate.of(2020,1,1), LocalDate.of(2099,1,1));
        scopes.record(valid, a, time.plusSeconds(3));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly(AuthorizationCatalog.AUTH_VIEWER);
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(AuthorizationCatalog.AUTH_VIEWER, "비활성 권한", false));
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
    }

    @Test
    void duplicateLatestTimestampAndSequenceAcrossLocalOrganizationsFailsClosed() {
        affiliate("viewer", "SSO_A", 0);
        scopes.record(direct("viewer", AuthorizationCatalog.AUTH_VIEWER), a, time);
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", true, 0));
        var duplicate = catalog.createScopedAssignment("viewer", "OTHER", AuthorizationCatalog.AUTH_VIEWER, AssignmentStatus.APPROVED,
                LocalDate.of(2020,1,1), LocalDate.of(2099,1,1));
        scopes.record(duplicate, a, time);
        assertThatThrownBy(() -> scopes.latest("viewer", a, null)).isInstanceOf(ApiException.class);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
        assertThat(effective.hasDirectAuthority("viewer", AuthorizationCatalog.AUTH_VIEWER)).isFalse();
    }

    @Test
    void metadataInvalidatesVersionsOnlyWhenEnabledAuthorizationIsAffected() {
        long initial = catalog.authorityVersion();
        affiliate("viewer", "SSO_A", 0);
        AuthorityAssignment assignment = direct("viewer", AuthorizationCatalog.AUTH_VIEWER);
        scopes.record(assignment, a, time);
        assertThat(catalog.authorityVersion()).isEqualTo(initial);
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", true, 0));
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 1);
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", true, 1));
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 1);
        affiliate("viewer", "SSO_B", 1);
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 2);
        var link = links.list().get(0);
        links.save(new AuthoritySsoHistoryLinkService.WriteLink(link.username(), link.organizationId(), link.authorityId(), link.assignmentType(),
                link.assignmentSequence(), "SSO_B", "TEAM", time, link.version()));
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 3);
        mappings.save(mapping("SSO_B", false, 1));
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 4);
        assertThat(effective.findEffectiveAuthorityIds("viewer")).isEmpty();
        mappings.save(mapping("SSO_A", false, 1));
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 4);
        scopes.save(new SsoAuthorityScopeService.WriteMode("viewer", false, 2));
        assertThat(catalog.authorityVersion()).isEqualTo(initial + 5);
    }

    @Test
    void adminActivationRequiresCurrentUsableDirectAdministratorAndVersion() {
        affiliate("admin", "SSO_A", 0);
        assertThatThrownBy(() -> scopes.save(new SsoAuthorityScopeService.WriteMode("admin", true, 0))).isInstanceOf(ApiException.class);
        scopes.record(direct("admin", AuthorizationCatalog.AUTH_SYSTEM_ADMIN), a, time);
        assertThat(scopes.save(new SsoAuthorityScopeService.WriteMode("admin", true, 0)).enabled()).isTrue();
        assertThatThrownBy(() -> scopes.save(new SsoAuthorityScopeService.WriteMode("admin", false, 0))).isInstanceOf(ApiException.class);
        assertThat(effective.findEffectiveAuthorityIds("admin")).containsExactly(AuthorizationCatalog.AUTH_SYSTEM_ADMIN);
    }

    @Test
    void immutableHistoricalScopeCanBeProcessedAfterMappingDisablesWithoutRelabeling() {
        affiliate("viewer", "SSO_A", 0);
        AuthorityAssignment original = direct("viewer", AuthorizationCatalog.AUTH_VIEWER);
        scopes.record(original, a, time);
        affiliate("viewer", "SSO_B", 1);
        mappings.save(mapping("SSO_A", false, 1));
        scopes.requireLatest(original, a);
        var processed = catalog.processScopedAssignment(original, AssignmentStatus.REVOKED,
                LocalDate.of(2020,1,1), LocalDate.of(2026,9,3));
        scopes.record(processed, a, time.plusSeconds(1));
        assertThat(scopes.scopeOf(processed)).contains(a);
        assertThat(scopes.requireCurrentScope("viewer")).isEqualTo(b);
        assertThatThrownBy(() -> scopes.record(processed, b, time.plusSeconds(2))).isInstanceOf(ApiException.class);
    }

    private AuthorityAssignment direct(String username, String authority) {
        return catalog.assignmentsFor(username).stream().filter(item -> item.type() == AssignmentType.DIRECT && item.authorityId().equals(authority)).findFirst().orElseThrow();
    }
    private void affiliate(String username, String office, long version) { users.save(new UserSsoOrganizationService.WriteAffiliation(username, office, "TEAM", version)); }
    private OrganizationMappingService.WriteMapping mapping(String office, boolean active, long version) {
        return new OrganizationMappingService.WriteMapping(office, "TEAM", office, "합성 부서", "HQ", "", active, version);
    }
}
