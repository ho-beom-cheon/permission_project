package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 메뉴·프로그램·기능의 세 가지 컨텍스트와 최종 업무 권한을 결합한 기능 인가를 검증한다.
 * 한 요소라도 일치하지 않거나 비활성이면 허용하지 않는 기본 거부 정책을 테스트한다.
 */
class ProgramAuthorizationServiceTest {

    private ProgramAuthorizationService service;

    /** 실제 서비스 연결 순서와 동일하게 권한 계산 → 메뉴 → 프로그램 인가 서비스를 구성한다. */
    @BeforeEach
    void setUp() {
        AuthorizationCatalog catalog = new AuthorizationCatalog();
        EffectiveAuthorityService authorityService = new EffectiveAuthorityService(
                catalog,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC));
        MenuAuthorizationService menuService = new MenuAuthorizationService(catalog, authorityService);
        service = new ProgramAuthorizationService(catalog, authorityService, menuService);
    }

    /** 조회 전용 권한은 읽기만 허용되고 저장·게시는 거부되는지 확인한다. */
    @Test
    void viewerCanReadButCannotWrite() {
        Authentication viewer = authentication("viewer");

        assertThat(service.findAllowedActions(viewer, "CONTENT_LIST", "CONTENT"))
                .extracting(ProgramAuthorizationService.ActionPermission::actionId)
                .containsExactly("CONTENT_READ");
        assertThat(service.isAllowed(
                viewer, "CONTENT_LIST", "CONTENT", "CONTENT_SAVE")).isFalse();
        assertThat(service.isAllowed(
                viewer, "CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH")).isFalse();
    }

    /** 직접 관리자와 유효한 위임 사용자가 동일한 콘텐츠 기능 집합을 얻는지 확인한다. */
    @Test
    void managerAndValidDelegateCanUseAllContentActions() {
        for (String username : List.of("manager", "delegate")) {
            assertThat(service.findAllowedActions(
                    authentication(username), "CONTENT_LIST", "CONTENT"))
                    .extracting(ProgramAuthorizationService.ActionPermission::actionId)
                    .containsExactly("CONTENT_READ", "CONTENT_SAVE", "CONTENT_PUBLISH");
        }
    }

    /** 메뉴나 프로그램 컨텍스트를 바꿔 전달해도 다른 기능 권한으로 전용되지 않는지 확인한다. */
    @Test
    void menuContextAndActionActivationAreBothChecked() {
        Authentication admin = authentication("admin");

        assertThat(service.findAllowedActions(admin, "SYSTEM_AUTH", "AUTHORITY"))
                .extracting(ProgramAuthorizationService.ActionPermission::actionId)
                .containsExactly("AUTHORITY_READ", "AUTHORITY_UPDATE");
        assertThat(service.findAllowedActions(admin, "CONTENT_LIST", "AUTHORITY")).isEmpty();
        assertThat(service.isAllowed(
                admin, "CONTENT_LIST", "CONTENT", "CONTENT_DELETE_OLD")).isFalse();
        assertThat(service.isAllowed(
                admin, "SYSTEM_AUTH", "CONTENT", "CONTENT_READ")).isFalse();
        assertThat(service.isAllowed(
                admin, "CONTENT_LIST", "CONTENT", "CONTENT_READ")).isTrue();
    }

    /** 업무 권한은 username 기반 원장에서 계산하며 프레임워크 역할은 로그인 여부만 표현한다. */
    private Authentication authentication(String username) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED_USER")));
    }
}
