package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkedAuthorityRuleServiceTest {

    private AuthorizationCatalog catalog;
    private LinkedAuthorityRuleService rules;

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        Set.of(
                "AI0201002002", "AI0201002003", "AI0201001002", "AI0101001001", "AI0102001001",
                "AI9999999999", "AB0204001002", "AB0204001001", "AB0102001001", "CY0201004004")
                .forEach(id -> catalog.saveAuthority(authority(id, id.substring(0, 2), true)));
        rules = new LinkedAuthorityRuleService(catalog);
    }

    @Test
    void expandsEveryFixedAiToAbRule() {
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002"), "100")).containsExactly("AI0201002002", "AB0204001002");
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002003"), "100")).containsExactly("AI0201002003", "AB0204001001");
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201001002"), "100"))
                .containsExactly("AI0201001002", "AB0204001002", "AB0204001001");
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0101001001"), "100")).containsExactly("AI0101001001", "AB0102001001");
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0102001001"), "100")).containsExactly("AI0102001001", "AB0102001001");
    }

    @Test
    void addsDepartmentLinkedCyAuthorityOnlyForCatalogAiSystem() {
        catalog.saveAuthority(authority("AI0000000001", "OTHER", true));

        var plan610 = rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI9999999999"), "610");
        var plan630 = rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002"), "630");

        assertThat(plan610.automaticallyAddedAuthorityIds()).containsExactly("CY0201004004");
        assertThat(plan630.automaticallyAddedAuthorityIds())
                .containsExactly("AB0204001002", "CY0201004004");
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0000000001"), "610")).containsExactly("AI0000000001");
    }

    @Test
    void deduplicatesSharedAndAlreadySelectedTargets() {
        var plan = rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002", "AI0201001002", "AB0204001002"), "100");

        assertThat(plan.automaticallyAddedAuthorityIds()).containsExactly("AB0204001001");
        assertThat(plan.expandedAuthorityIds()).containsOnlyOnce("AB0204001002", "AB0204001001");
    }

    @Test
    void excludesSatisfiedAutomaticTargetButRejectsSatisfiedSelectedSource() {
        var plan = rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201001002"), "100", Set.of("AB0204001002"));

        assertThat(plan.automaticallyAddedAuthorityIds()).containsExactly("AB0204001001");
        assertThat(plan.expandedAuthorityIds()).containsExactly("AI0201001002", "AB0204001001");
        assertThatThrownBy(() -> rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201001002"), "100", Set.of("AI0201001002")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void rejectsMissingOrInactiveSelectedAndAutomaticAuthorities() {
        assertConflict(() -> rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0000000999"), "100"));

        catalog.saveAuthority(authority("AI0201002002", "AI", false));
        assertConflict(() -> rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002"), "100"));

        AuthorizationCatalog targetMissingCatalog = new AuthorizationCatalog();
        targetMissingCatalog.saveAuthority(authority("AI0201002002", "AI", true));
        LinkedAuthorityRuleService targetMissingRules = new LinkedAuthorityRuleService(targetMissingCatalog);
        assertConflict(() -> targetMissingRules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002"), "100"));

        AuthorizationCatalog targetInactiveCatalog = new AuthorizationCatalog();
        targetInactiveCatalog.saveAuthority(authority("AI0201002002", "AI", true));
        targetInactiveCatalog.saveAuthority(authority("AB0204001002", "AB", false));
        LinkedAuthorityRuleService targetInactiveRules = new LinkedAuthorityRuleService(targetInactiveCatalog);
        assertConflict(() -> targetInactiveRules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002"), "100"));
    }

    @Test
    void directAbSelectionDoesNotCreateReverseLinkAndChangeNeverExpands() {
        assertThat(rules.expand(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AB0204001002"), "610")).containsExactly("AB0204001002");
        assertThat(rules.expand(AuthorityRequestService.RequestKind.CHANGE,
                Set.of("AI0201001002"), "610")).containsExactly("AI0201001002");
    }

    @Test
    void planRejectsMissingKindSetSatisfiedSetOrBlankAuthorityId() {
        assertThatThrownBy(() -> rules.plan(null, Set.of("AI0201002002"), "610"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.plan(AuthorityRequestService.RequestKind.GRANT, null, "610"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.plan(AuthorityRequestService.RequestKind.GRANT,
                Set.of("AI0201002002"), "610", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.plan(AuthorityRequestService.RequestKind.GRANT, Set.of(""), "610"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private static AuthorityDefinition authority(String id, String systemId, boolean active) {
        return new AuthorityDefinition(id, id, active, systemId, null, "");
    }
}
