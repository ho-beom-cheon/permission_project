package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.web.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 권한 관리 서비스의 직접·위임·회수와 메뉴 계층 검증을 웹 계층과 분리해 확인한다. */
class AdminServiceTest {

    private AuthorizationCatalog catalog;
    private EffectiveAuthorityService effectiveAuthorityService;
    private AdminService adminService;

    /** 모든 테스트가 같은 기준일을 사용하도록 고정 Clock과 새 인메모리 카탈로그를 구성한다. */
    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        effectiveAuthorityService = new EffectiveAuthorityService(catalog, clock);
        adminService = new AdminService(
                catalog, effectiveAuthorityService, new AuditEventService());
    }

    /** 직접 권한을 추가하면 즉시 효력이 생기고 회수 최신 이력이 추가되면 다시 제외되는지 확인한다. */
    @Test
    void directAuthorityCanBeGrantedAndRevoked() {
        adminService.assignAuthority(
                "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER,
                AuthorizationCatalog.AssignmentType.DIRECT,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("viewer"))
                .contains(AuthorizationCatalog.AUTH_CONTENT_MANAGER);

        adminService.revokeAuthority(
                "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER,
                AuthorizationCatalog.AssignmentType.DIRECT);

        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("viewer"))
                .doesNotContain(AuthorizationCatalog.AUTH_CONTENT_MANAGER);
    }

    /** 위임 원천은 같은 조직에서 해당 권한을 직접 보유해야 하며 연쇄 위임은 거부되는지 확인한다. */
    @Test
    void delegatedAuthorityNeedsDirectSource() {
        adminService.assignAuthority(
                "viewer",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER,
                AuthorizationCatalog.AssignmentType.DELEGATED,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                "manager");

        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("viewer"))
                .contains(AuthorizationCatalog.AUTH_CONTENT_MANAGER);

        assertThatThrownBy(() -> adminService.assignAuthority(
                "expired",
                AuthorizationCatalog.AUTH_CONTENT_MANAGER,
                AuthorizationCatalog.AssignmentType.DELEGATED,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                "delegate"))
                .isInstanceOf(ApiException.class);
    }

    /** 기존 자식 메뉴를 새 상위로 지정해 순환을 만드는 메뉴 수정이 저장 전에 차단되는지 확인한다. */
    @Test
    void menuCycleIsRejected() {
        assertThatThrownBy(() -> adminService.saveMenu(
                new AuthorizationCatalog.MenuDefinition(
                        "SYSTEM",
                        "SYSTEM_MENU",
                        "시스템 관리",
                        null,
                        30,
                        true,
                        true,
                        false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("순환");
    }

    /** 권한·프로그램·기능과 권한별 기능 매핑이 관리 원장에 함께 저장되는지 확인한다. */
    @Test
    void programMasterAndGrantCanBeManaged() {
        adminService.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                "AUTH_REPORT_MANAGER", "보고서 관리자", true));
        adminService.saveMenu(new AuthorizationCatalog.MenuDefinition(
                "REPORT_LIST", "CONTENT", "보고서 조회", "#report", 80,
                true, true, false));
        adminService.saveProgram(new AuthorizationCatalog.ProgramDefinition(
                "REPORT", "보고서 관리", "보고서 조회와 출력", true));
        AuthorizationCatalog.ProgramActionDefinition action = adminService.saveAction(
                new AuthorizationCatalog.ProgramActionDefinition(
                        "REPORT_LIST", "REPORT", "REPORT_READ", "보고서 조회",
                        "btnReportRead", 10, true));
        adminService.setMenuGrant("AUTH_REPORT_MANAGER", "REPORT_LIST", true);
        adminService.setActionGrant("AUTH_REPORT_MANAGER", action.key(), true);

        assertThat(adminService.loadAuthorities().authorities())
                .extracting(AuthorizationCatalog.AuthorityDefinition::id)
                .contains("AUTH_REPORT_MANAGER");
        assertThat(adminService.loadPrograms().programs())
                .extracting(AuthorizationCatalog.ProgramDefinition::id)
                .contains("REPORT");
        assertThat(catalog.actionGrantsFor("AUTH_REPORT_MANAGER")).contains(action.key());
    }

    /** 핵심 관리 권한과 관리 프로그램을 비활성화해 관리자 자신을 잠그는 변경을 차단한다. */
    @Test
    void coreAdminControlsStayActive() {
        assertThatThrownBy(() -> adminService.saveAuthority(
                new AuthorizationCatalog.AuthorityDefinition(
                        AuthorizationCatalog.AUTH_SYSTEM_ADMIN, "시스템 관리자", false)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> adminService.saveProgram(
                new AuthorizationCatalog.ProgramDefinition(
                        "AUTHORITY", "권한 관리", "관리 기능", false)))
                .isInstanceOf(ApiException.class);
    }
}
