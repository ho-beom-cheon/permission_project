package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser("admin")
class SsoScopedAdministrationTest {
    @Autowired DirectoryService directory;
    @Autowired OrganizationMappingService mappings;
    @Autowired UserSsoOrganizationService affiliations;
    @Autowired SsoAuthorityScopeService scopes;
    @Autowired AuthorizationCatalog catalog;
    @Autowired EffectiveAuthorityService effective;
    @Autowired AdminService admin;
    @Autowired AuthorityRevocationService revocations;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String username, a, b;
    long affiliationVersion;

    @BeforeEach void fixture() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        username = "scoped_" + key; a = "A_" + key; b = "B_" + key; affiliationVersion = 0;
        directory.savePerson(new DirectoryService.Person(username, username, "HQ", "", "", "", "", "", "", true, Set.of()));
        for (String sso : List.of(a, b)) mappings.save(new OrganizationMappingService.WriteMapping(sso, "DEP", sso, "합성 부서", "HQ", "", true, 0));
        move(a);
        scopes.save(new SsoAuthorityScopeService.WriteMode(username, true, 0));
    }

    void move(String office) { affiliations.save(new UserSsoOrganizationService.WriteAffiliation(username, office, "DEP", affiliationVersion++)); }
    void grant(String authority) { admin.assignAuthority(username, authority, AssignmentType.DIRECT, LocalDate.now(), LocalDate.now().plusYears(1), null); }
    AuthorityRevocationService.Request bulk(long version) {
        return new AuthorityRevocationService.Request(List.of(new AuthorityRevocationService.Target(username, "HQ")), "INFO", true, version, "SSO 범위 회수");
    }

    @Test void directGrantAndRevokeUseCurrentSsoEvenWhenLocalOrganizationAndAuthorityAreSame() {
        grant("AUTH_VIEWER"); move(b);
        assertThat(effective.findEffectiveAuthorityIds(username)).isEmpty();
        grant("AUTH_VIEWER");
        assertThatThrownBy(() -> grant("AUTH_VIEWER")).isInstanceOf(ApiException.class);
        move(a); admin.revokeAuthority(username, "AUTH_VIEWER", AssignmentType.DIRECT, "회수 사유");
        assertThat(effective.findEffectiveAuthorityIds(username)).isEmpty();
        move(b); assertThat(effective.findEffectiveAuthorityIds(username)).containsExactly("AUTH_VIEWER");
    }

    @Test void bulkPreviewInvalidatesOnSsoMoveAndRevokeLeavesOtherScopeApproved() {
        grant("AUTH_VIEWER"); move(b); grant("AUTH_CONTENT_MANAGER");
        long version = catalog.authorityVersion();
        assertThat(revocations.preview(bulk(version)).assignments()).extracting(AuthorizationCatalog.AuthorityAssignment::authorityId)
                .containsExactly("AUTH_CONTENT_MANAGER");
        move(a);
        assertThatThrownBy(() -> revocations.revoke(bulk(version))).isInstanceOf(ApiException.class);
        move(b); revocations.revoke(bulk(catalog.authorityVersion()));
        assertThat(effective.findEffectiveAuthorityIds(username)).isEmpty();
        move(a); assertThat(effective.findEffectiveAuthorityIds(username)).containsExactly("AUTH_VIEWER");
    }

    @Test void unsupportedDelegationCannotCrossScopedAndLegacyUsers() {
        grant("AUTH_VIEWER");
        assertThatThrownBy(() -> admin.assignAuthority(username, "AUTH_CONTENT_MANAGER", AssignmentType.DELEGATED,
                LocalDate.now(), LocalDate.now().plusDays(1), "manager")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> admin.assignAuthority("viewer", "AUTH_VIEWER", AssignmentType.DELEGATED,
                LocalDate.now(), LocalDate.now().plusDays(1), username)).isInstanceOf(ApiException.class);
    }

    @Test void existingAuthenticatedRequestsLoseOldScopeAccessImmediately() throws Exception {
        grant("AUTH_SYSTEM_ADMIN");
        mvc.perform(get("/api/admin/authority-view").with(user(username))).andExpect(status().isOk());
        move(b);
        mvc.perform(get("/api/admin/authority-view").with(user(username))).andExpect(status().isForbidden());
        move(a);
        mvc.perform(get("/api/admin/authority-view").with(user(username))).andExpect(status().isOk());
    }

    @Test void modeApiRequiresAdministrationCsrfAndCurrentVersion() throws Exception {
        String path = "/api/admin/sso-authorizations";
        byte[] stale = json.writeValueAsBytes(new SsoAuthorityScopeService.WriteMode(username, false, 0));
        mvc.perform(get(path).with(user(username))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(user(username)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin")).contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict());
        assertThat(scopes.isEnabled(username)).isTrue();
    }
}
