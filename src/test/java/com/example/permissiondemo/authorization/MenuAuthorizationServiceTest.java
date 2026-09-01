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
 * 권한별 메뉴 노출, 상위 폴더 자동 포함, 숨김 메뉴의 표시/접근 분리를 검증한다.
 * 화면 트리와 서버 직접 접근 판정이 서로 다른 계약임을 테스트로 고정한다.
 */
class MenuAuthorizationServiceTest {

    private MenuAuthorizationService service;

    /** 권한 계산과 메뉴 계산이 동일한 고정 시각의 카탈로그를 사용하도록 구성한다. */
    @BeforeEach
    void setUp() {
        AuthorizationCatalog catalog = new AuthorizationCatalog();
        EffectiveAuthorityService authorityService = new EffectiveAuthorityService(
                catalog,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC));
        service = new MenuAuthorizationService(catalog, authorityService);
    }

    /** 조회자는 공개 홈과 허용된 콘텐츠 잎 메뉴, 그 조상 폴더만 볼 수 있는지 확인한다. */
    @Test
    void viewerGetsPublicMenuAndAuthorizedLeafWithItsParent() {
        List<MenuAuthorizationService.MenuNode> menus =
                service.findAuthorizedMenuTree(authentication("viewer"));

        assertThat(menus).extracting(MenuAuthorizationService.MenuNode::id)
                .containsExactly("HOME", "CONTENT");
        assertThat(menus.get(1).children())
                .extracting(MenuAuthorizationService.MenuNode::id)
                .containsExactly("CONTENT_LIST");
        assertThat(service.canAccessMenu(authentication("viewer"), "CONTENT")).isFalse();
        assertThat(service.canAccessMenu(authentication("viewer"), "CONTENT_LIST")).isTrue();
    }

    /** 관리자는 시스템 하위 메뉴에 접근하지만 숨김 메뉴는 트리에 노출되지 않는지 확인한다. */
    @Test
    void adminGetsSystemChildrenButNotHiddenMenu() {
        Authentication admin = authentication("admin");
        List<MenuAuthorizationService.MenuNode> menus = service.findAuthorizedMenuTree(admin);

        assertThat(menus).extracting(MenuAuthorizationService.MenuNode::id)
                .containsExactly("HOME", "CONTENT", "SYSTEM");
        MenuAuthorizationService.MenuNode system = menus.get(2);
        assertThat(system.children()).extracting(MenuAuthorizationService.MenuNode::id)
                .containsExactly(
                        "SYSTEM_AUTH_MASTER",
                        "SYSTEM_AUTH",
                        "SYSTEM_MENU",
                        "SYSTEM_PROGRAM",
                        "SYSTEM_CODE");
        assertThat(service.canAccessMenu(admin, "HIDDEN_MENU")).isTrue();
    }

    /**
     * Spring Security 로그인 객체를 최소 형태로 만들어 업무 권한 계산에 전달한다.
     * 업무 권한 ID는 GrantedAuthority로 주입하지 않고 username을 통해 서버 원장에서 계산한다.
     */
    private Authentication authentication(String username) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED_USER")));
    }
}
