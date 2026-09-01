package com.example.permissiondemo.web;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.authorization.AdminService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.common.CommonCodeService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 권한, 메뉴와 공통코드 관리 화면이 사용하는 관리자 전용 API를 제공한다.
 * 모든 조회와 변경은 서버의 프로그램 기능 권한을 다시 검사하고 변경 요청은 CSRF 보호를 받는다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CommonCodeService commonCodeService;

    public AdminController(AdminService adminService, CommonCodeService commonCodeService) {
        this.adminService = adminService;
        this.commonCodeService = commonCodeService;
    }

    /** 전체 권한 마스터와 사용자별 유효 권한·변경 이력을 조회한다. */
    @GetMapping("/authority-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<AdminService.AuthorityAdminView> authorities() {
        return ApiResponse.ok(adminService.loadAuthorities());
    }

    /** 권한 마스터 한 건을 신규 등록하거나 표시명·활성 상태를 수정한다. */
    @PostMapping("/authorities/{authorityId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.AuthorityDefinition> saveAuthority(
            @PathVariable String authorityId,
            @Valid @RequestBody AuthoritySaveRequest request) {
        return ApiResponse.ok(adminService.saveAuthority(
                new AuthorizationCatalog.AuthorityDefinition(
                        authorityId, request.name(), request.active())));
    }

    /** 사용자에게 직접 또는 위임 권한을 승인 상태로 부여한다. */
    @PostMapping("/users/{username}/authorities")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.AuthorityAssignment> assignAuthority(
            @PathVariable String username,
            @Valid @RequestBody AuthorityAssignRequest request) {
        return ApiResponse.ok(adminService.assignAuthority(
                username,
                request.authorityId(),
                request.type(),
                request.validFrom(),
                request.validTo(),
                request.delegatedBy()));
    }

    /** 선택한 직접 또는 위임 권한에 최신 회수 이력을 추가한다. */
    @DeleteMapping("/users/{username}/authorities/{authorityId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.AuthorityAssignment> revokeAuthority(
            @PathVariable String username,
            @PathVariable String authorityId,
            @RequestParam AuthorizationCatalog.AssignmentType type) {
        return ApiResponse.ok(adminService.revokeAuthority(username, authorityId, type));
    }

    /** 전체 메뉴 속성과 권한별 직접 허용 매핑을 조회한다. */
    @GetMapping("/menu-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<AdminService.MenuAdminView> menus() {
        return ApiResponse.ok(adminService.loadMenus());
    }

    /** 메뉴 한 건의 계층, 경로, 정렬, 활성·표시·공용 속성을 저장한다. */
    @PostMapping("/menus/{menuId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.MenuDefinition> saveMenu(
            @PathVariable String menuId,
            @Valid @RequestBody MenuSaveRequest request) {
        return ApiResponse.ok(adminService.saveMenu(new AuthorizationCatalog.MenuDefinition(
                menuId,
                request.parentId(),
                request.name(),
                request.path(),
                request.sortOrder(),
                request.active(),
                request.displayed(),
                request.publicMenu())));
    }

    /** 한 권한이 메뉴에 직접 접근할 수 있는지 여부를 추가 또는 제거한다. */
    @PutMapping("/authorities/{authorityId}/menus/{menuId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<Set<String>> setMenuGrant(
            @PathVariable String authorityId,
            @PathVariable String menuId,
            @Valid @RequestBody MenuGrantRequest request) {
        return ApiResponse.ok(adminService.setMenuGrant(
                authorityId, menuId, request.granted()));
    }

    /** 프로그램·기능과 권한별 기능 허용 매핑의 전체 관리 원본을 조회한다. */
    @GetMapping("/program-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<AdminService.ProgramAdminView> programs() {
        return ApiResponse.ok(adminService.loadPrograms());
    }

    /** 프로그램 마스터 한 건을 신규 등록하거나 표시명·설명·활성 상태를 수정한다. */
    @PostMapping("/programs/{programId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.ProgramDefinition> saveProgram(
            @PathVariable String programId,
            @Valid @RequestBody ProgramSaveRequest request) {
        return ApiResponse.ok(adminService.saveProgram(
                new AuthorizationCatalog.ProgramDefinition(
                        programId, request.name(), request.description(), request.active())));
    }

    /** 메뉴·프로그램 문맥에 속한 기능 한 건을 신규 등록하거나 수정한다. */
    @PostMapping("/program-actions/{menuId}/{programId}/{actionId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<AuthorizationCatalog.ProgramActionDefinition> saveAction(
            @PathVariable String menuId,
            @PathVariable String programId,
            @PathVariable String actionId,
            @Valid @RequestBody ProgramActionSaveRequest request) {
        return ApiResponse.ok(adminService.saveAction(
                new AuthorizationCatalog.ProgramActionDefinition(
                        menuId,
                        programId,
                        actionId,
                        request.label(),
                        request.componentId(),
                        request.sortOrder(),
                        request.active())));
    }

    /** 한 권한이 특정 메뉴·프로그램 기능을 실행할 수 있는지 허용 또는 해제한다. */
    @PutMapping("/authorities/{authorityId}/program-actions/{menuId}/{programId}/{actionId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<Set<AuthorizationCatalog.ProgramActionKey>> setActionGrant(
            @PathVariable String authorityId,
            @PathVariable String menuId,
            @PathVariable String programId,
            @PathVariable String actionId,
            @Valid @RequestBody ActionGrantRequest request) {
        return ApiResponse.ok(adminService.setActionGrant(
                authorityId,
                new AuthorizationCatalog.ProgramActionKey(menuId, programId, actionId),
                request.granted()));
    }

    /** 공통코드 관리용 전체 그룹별 건수와 현재 버전을 조회한다. */
    @GetMapping("/common-code-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_SAVE')")
    public ApiResponse<CodeAdminView> commonCodes() {
        return ApiResponse.ok(new CodeAdminView(
                commonCodeService.groupSummary(),
                commonCodeService.groupVersions()));
    }

    /** 사용자 권한 부여에 필요한 권한, 유형, 기간과 선택적 위임 원천 사용자다. */
    public record AuthorityAssignRequest(
            @NotBlank
            @Pattern(regexp = "[A-Z0-9_]{1,50}")
            String authorityId,
            @NotNull AuthorizationCatalog.AssignmentType type,
            LocalDate validFrom,
            LocalDate validTo,
            @Pattern(regexp = "[A-Za-z0-9_]{1,50}")
            String delegatedBy) {
    }

    /** 권한 마스터 표시명과 사용 가능 여부를 저장하는 요청이다. */
    public record AuthoritySaveRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull Boolean active) {
    }

    /** 메뉴 마스터 한 건의 변경 가능한 속성이다. 메뉴 ID는 URL 경로에서 별도로 받는다. */
    public record MenuSaveRequest(
            @Pattern(regexp = "[A-Z0-9_]{1,50}")
            String parentId,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "#[A-Za-z0-9_-]{1,100}")
            String path,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean active,
            @NotNull Boolean displayed,
            @NotNull Boolean publicMenu) {
    }

    /** 권한-메뉴 직접 허용 매핑을 추가하거나 제거하기 위한 요청이다. */
    public record MenuGrantRequest(@NotNull Boolean granted) {
    }

    /** 프로그램 마스터 표시명, 설명과 사용 가능 여부를 저장하는 요청이다. */
    public record ProgramSaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) String description,
            @NotNull Boolean active) {
    }

    /** 프로그램 기능 표시명, 화면 컴포넌트, 정렬과 사용 가능 여부를 저장하는 요청이다. */
    public record ProgramActionSaveRequest(
            @NotBlank @Size(max = 100) String label,
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,99}") String componentId,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean active) {
    }

    /** 권한-프로그램 기능 직접 허용 매핑 변경 요청이다. */
    public record ActionGrantRequest(@NotNull Boolean granted) {
    }

    /** 공통코드 그룹별 항목 수와 캐시 갱신 판단용 버전을 묶은 관리 응답이다. */
    public record CodeAdminView(Map<String, Integer> groups, Map<String, Long> versions) {
    }
}
