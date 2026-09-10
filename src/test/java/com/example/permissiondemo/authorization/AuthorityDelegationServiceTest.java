package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.example.permissiondemo.authorization.AuthorityDelegationService.Delegation;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorityDelegationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T01:02:03Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AuthorizationCatalog catalog;
    private AuthorityDelegationService service;

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        service = new AuthorityDelegationService(catalog, new EffectiveAuthorityService(catalog, clock), clock);
    }

    @Test
    void createsOnlyFromCurrentDirectAuthorityWithSourceDatesAndReason() {
        assertThat(service.candidateAuthorities("manager")).extracting(AuthorizationCatalog.AuthorityDefinition::id)
                .containsExactly(AuthorizationCatalog.AUTH_CONTENT_MANAGER);

        Delegation saved = service.create("manager", "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "업무 대행");

        assertThat(saved.delegationSequence()).isEqualTo(1);
        assertThat(saved.validFrom()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(saved.validTo()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(saved.reason()).isEqualTo("업무 대행");
        assertThat(saved.registeredAt()).isEqualTo(NOW);
        assertThat(service.activeForRecipient("viewer")).containsExactly(saved);
    }

    @Test
    void rejectsSelfBlankOrLongReasonAndAuthorityNotHeldDirectly() {
        assertThatThrownBy(() -> service.create("manager", "manager",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "대행"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("manager", "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("manager", "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "가".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("viewer", "manager",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "대행"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void enforcesOneActiveDelegationPerDelegatorAndRecipientDuplicateAuthority() {
        service.create("manager", "viewer", AuthorizationCatalog.AUTH_CONTENT_MANAGER, "첫 위임");

        assertThatThrownBy(() -> service.create("manager", "expired",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "두 번째 위임"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode()).isEqualTo(ErrorCode.CONFLICT);

        catalog.saveAssignment("admin", AuthorizationCatalog.AUTH_CONTENT_MANAGER, AssignmentType.DIRECT,
                LocalDate.of(2020, 1, 1), LocalDate.of(9999, 12, 31), null);
        assertThatThrownBy(() -> service.create("admin", "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "중복 권한"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void cancellationPhysicallyDeletesAndAllowsAReplacement() {
        Delegation first = service.create("manager", "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "첫 위임");

        assertThat(service.cancel("manager")).isEqualTo(first);
        assertThat(service.all()).isEmpty();
        assertThat(service.active("manager")).isEmpty();

        Delegation replacement = service.create("manager", "expired",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER, "교체 위임");
        assertThat(replacement.delegationSequence()).isEqualTo(2);
    }

    @Test
    void snapshotRestorePreservesRowsAndAdvancesFromMaximumSequence() {
        Delegation stored = new Delegation(7, "manager", AuthorizationCatalog.AUTH_CONTENT_MANAGER, "viewer",
                LocalDate.of(2026, 9, 4), LocalDate.of(9999, 12, 31), "복원", NOW);
        service.restoreState(new AuthorityDelegationService.StoredState(List.of(stored), 2));

        assertThat(service.snapshotState()).isEqualTo(
                new AuthorityDelegationService.StoredState(List.of(stored), 7));
        service.cancel("manager");
        assertThat(service.create("manager", "expired", AuthorizationCatalog.AUTH_CONTENT_MANAGER, "신규")
                .delegationSequence()).isEqualTo(8);
    }
}
