package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 현재 조직·승인 이력·권한 활성 상태·위임 기간을 모두 반영한 최종 권한 계산을 검증한다.
 * 테스트 시간을 고정하여 위임 만료 여부가 실행 날짜에 따라 달라지지 않게 한다.
 */
class EffectiveAuthorityServiceTest {

    /** 각 테스트가 독립적인 인메모리 권한 원장과 동일한 기준 시각을 사용하도록 재생성한다. */
    private EffectiveAuthorityService service;

    @BeforeEach
    void setUp() {
        AuthorizationCatalog catalog = new AuthorizationCatalog();
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        service = new EffectiveAuthorityService(catalog, clock);
    }

    /** 직접 부여 권한과 유효한 위임 권한이 중복 없이 합산되는지 확인한다. */
    @Test
    void directAndValidDelegatedAuthoritiesAreCombined() {
        assertThat(service.findEffectiveAuthorityIds("delegate"))
                .containsExactlyInAnyOrder(
                        AuthorizationCatalog.AUTH_VIEWER,
                        AuthorizationCatalog.AUTH_CONTENT_MANAGER);
    }

    /** 종료 시각이 지난 위임은 최종 권한에서 제외되는지 확인한다. */
    @Test
    void expiredDelegationIsExcluded() {
        assertThat(service.findEffectiveAuthorityIds("expired"))
                .containsExactly(AuthorizationCatalog.AUTH_VIEWER);
    }

    /** 대기 중인 배정 및 비활성 권한 마스터가 최종 권한을 만들지 않는지 확인한다. */
    @Test
    void pendingAndDisabledAuthoritiesAreExcluded() {
        assertThat(service.findEffectiveAuthorityIds("viewer"))
                .containsExactly(AuthorizationCatalog.AUTH_VIEWER);
        assertThat(service.findEffectiveAuthorityIds("admin"))
                .containsExactly(AuthorizationCatalog.AUTH_SYSTEM_ADMIN);
    }

    /** 최신 취소 이력과 조직 이동이 과거 승인 이력보다 우선하는지 확인한다. */
    @Test
    void latestRevocationAndPreviousOrganizationOverrideOldApproval() {
        assertThat(service.findEffectiveAuthorityIds("revoked")).isEmpty();
        assertThat(service.findEffectiveAuthorityIds("moved")).isEmpty();
    }

    /** 위임자의 원본 권한이 취소되면 수임자에게 남은 위임도 무효가 되는지 확인한다. */
    @Test
    void delegationIsRejectedWhenSourceAuthorityWasRevoked() {
        assertThat(service.findEffectiveAuthorityIds("orphanDelegate"))
                .containsExactly(AuthorizationCatalog.AUTH_VIEWER);
    }

    /** 인증 사용자 원장에 없는 이름은 업무 권한이 없는 것으로 닫히는지 확인한다. */
    @Test
    void unknownUserHasNoBusinessAuthority() {
        assertThat(service.findEffectiveAuthorityIds("unknown")).isEmpty();
    }
}
