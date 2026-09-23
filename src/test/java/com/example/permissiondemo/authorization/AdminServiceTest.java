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

    /** 역할별 운영 담당자와 조직 마스터가 서로 연결된 충분한 초기 원장으로 제공되는지 확인한다. */
    @Test
    void operationalSeedDataCoversSpecializedManagementRoles() {
        assertThat(catalog.organizations())
                .extracting(AuthorizationCatalog.OrganizationDefinition::id)
                .contains("HQ", "PLATFORM", "CONTENT", "AUDIT", "BRANCH_SEOUL", "BRANCH_BUSAN");
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("authorityManager"))
                .containsExactly(AuthorizationCatalog.AUTH_AUTHORITY_MANAGER);
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("programManager"))
                .containsExactly(AuthorizationCatalog.AUTH_PROGRAM_MANAGER);
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("projectManager"))
                .containsExactly(AuthorizationCatalog.AUTH_PROJECT_MANAGER);
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("codeManager"))
                .containsExactly(AuthorizationCatalog.AUTH_CODE_MANAGER);
        assertThat(catalog.programActions()).hasSizeGreaterThan(25);
    }

    /** 관리 검색 조건이 권한·사용자·메뉴·프로그램·기능 원장에 일관되게 적용되는지 확인한다. */
    @Test
    void managementCatalogSupportsProductSearchFilters() {
        assertThat(catalog.searchAuthorities("project", true))
                .extracting(AuthorizationCatalog.AuthorityDefinition::id)
                .containsExactly(AuthorizationCatalog.AUTH_PROJECT_MANAGER);
        assertThat(catalog.searchUsers("manager", "HQ"))
                .extracting(AuthorizationCatalog.UserProfile::username)
                .contains("authorityManager", "projectManager")
                .doesNotContain("programManager");
        assertThat(catalog.searchMenus("공통코드", true, true))
                .extracting(AuthorizationCatalog.MenuDefinition::id)
                .containsExactly("SYSTEM_CODE");
        assertThat(catalog.searchProgramActions("SYSTEM_CODE", "COMMON_CODE", "삭제", true))
                .extracting(AuthorizationCatalog.ProgramActionDefinition::actionId)
                .containsExactly("COMMON_CODE_DELETE");
    }

    /** 비활성·미사용 권한을 삭제하면 메뉴와 기능 매핑도 고아로 남지 않는지 확인한다. */
    @Test
    void deletingUnusedAuthorityCleansAllMappings() {
        adminService.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                "AUTH_TEMP_OPERATOR", "임시 운영자", true));
        adminService.setMenuGrant("AUTH_TEMP_OPERATOR", "CONTENT_LIST", true);
        adminService.setActionGrant(
                "AUTH_TEMP_OPERATOR",
                new AuthorizationCatalog.ProgramActionKey(
                        "CONTENT_LIST", "CONTENT", "CONTENT_READ"),
                true);
        adminService.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                "AUTH_TEMP_OPERATOR", "임시 운영자", false));

        adminService.deleteAuthority("AUTH_TEMP_OPERATOR");

        assertThat(catalog.findAuthority("AUTH_TEMP_OPERATOR")).isEmpty();
        assertThat(catalog.allMenuGrants()).doesNotContainKey("AUTH_TEMP_OPERATOR");
        assertThat(catalog.allActionGrants()).doesNotContainKey("AUTH_TEMP_OPERATOR");
    }

    /** 메뉴 삭제 시 해당 메뉴의 기능과 모든 권한 매핑까지 함께 정리되는지 확인한다. */
    @Test
    void deletingLeafMenuCleansProgramActionOrphans() {
        adminService.saveMenu(new AuthorizationCatalog.MenuDefinition(
                "TEMP_MENU", "CONTENT", "임시 메뉴", "#temp", 90,
                true, false, false));
        AuthorizationCatalog.ProgramActionDefinition action = adminService.saveAction(
                new AuthorizationCatalog.ProgramActionDefinition(
                        "TEMP_MENU", "CONTENT", "TEMP_READ", "임시 조회",
                        "btnTempRead", 10, true));
        adminService.setMenuGrant(AuthorizationCatalog.AUTH_VIEWER, "TEMP_MENU", true);
        adminService.setActionGrant(AuthorizationCatalog.AUTH_VIEWER, action.key(), true);
        adminService.saveMenu(new AuthorizationCatalog.MenuDefinition(
                "TEMP_MENU", "CONTENT", "임시 메뉴", "#temp", 90,
                false, false, false));

        adminService.deleteMenu("TEMP_MENU");

        assertThat(catalog.menus())
                .extracting(AuthorizationCatalog.MenuDefinition::id)
                .doesNotContain("TEMP_MENU");
        assertThat(catalog.programActions())
                .extracting(AuthorizationCatalog.ProgramActionDefinition::actionId)
                .doesNotContain("TEMP_READ");
        assertThat(catalog.actionGrantsFor(AuthorizationCatalog.AUTH_VIEWER))
                .doesNotContain(action.key());
    }

    /** 마지막 직접 시스템 관리자 회수를 차단해 관리 기능 전체 잠금을 방지한다. */
    @Test
    void lastSystemAdministratorCannotBeRevoked() {
        adminService.revokeAuthority(
                "admin", AuthorizationCatalog.AUTH_SYSTEM_ADMIN,
                AuthorizationCatalog.AssignmentType.DIRECT);

        assertThatThrownBy(() -> adminService.revokeAuthority(
                "backupAdmin", AuthorizationCatalog.AUTH_SYSTEM_ADMIN,
                AuthorizationCatalog.AssignmentType.DIRECT))
                .isInstanceOf(ApiException.class);
    }
}
