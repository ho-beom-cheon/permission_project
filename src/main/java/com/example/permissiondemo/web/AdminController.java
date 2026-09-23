package com.example.permissiondemo.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.authorization.AdminService;
import com.example.permissiondemo.authorization.AdminQueryService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.SortDirection;
import com.example.permissiondemo.project.ProjectModels;
import com.example.permissiondemo.project.ProjectService;
import com.example.permissiondemo.security.CurrentUserContext;

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
    private final AdminQueryService adminQueryService;
    private final CommonCodeService commonCodeService;
    private final ProjectService projectService;
    private final CurrentUserContext userContext;

    public AdminController(
            AdminService adminService,
            AdminQueryService adminQueryService,
            CommonCodeService commonCodeService,
            ProjectService projectService,
            CurrentUserContext userContext) {
        this.adminService = adminService;
        this.adminQueryService = adminQueryService;
        this.commonCodeService = commonCodeService;
        this.projectService = projectService;
        this.userContext = userContext;
    }

    /** 전체 권한 마스터와 사용자별 유효 권한·변경 이력을 조회한다. */
    @GetMapping("/authority-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<AdminService.AuthorityAdminView> authorities(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.ok(adminService.loadAuthorities(keyword, organizationId, active));
    }

    /** 권한 부여 필터와 편집 화면에 사용하는 조직 기준정보를 반환한다. */
    @GetMapping("/organizations")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<java.util.List<AuthorizationCatalog.OrganizationDefinition>> organizations() {
        return ApiResponse.ok(adminService.loadOrganizations());
    }

    @GetMapping("/authorities")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AuthorizationCatalog.AuthorityDefinition>> authorityList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) AdminQueryService.AuthoritySort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(adminQueryService.authorities(
                keyword, active, sort, direction, PageQuery.of(page, size)));
    }

    @GetMapping("/user-authorities")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AdminService.UserAuthorityView>> userAuthorityList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) AuthorizationCatalog.AssignmentType assignmentType,
            @RequestParam(required = false) Boolean revoked,
            @RequestParam(required = false) AdminQueryService.UserAuthoritySort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(adminQueryService.userAuthorities(
                keyword, organizationId, assignmentType, revoked, sort, direction,
                PageQuery.of(page, size)));
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

    /** 사용 중지되고 어떤 사용자·메뉴·기능에도 연결되지 않은 권한만 삭제한다. */
    @DeleteMapping("/authorities/{authorityId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<Map<String, String>> deleteAuthority(@PathVariable String authorityId) {
        adminService.deleteAuthority(authorityId);
        return ApiResponse.ok(Map.of("authorityId", authorityId));
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
            + "'SYSTEM_MENU', 'MENU', 'MENU_READ')")
    public ApiResponse<AdminService.MenuAdminView> menus(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean displayed) {
        return ApiResponse.ok(adminService.loadMenus(keyword, active, displayed));
    }

    /** 메뉴 마스터를 안전한 정렬과 제한된 페이지로 조회한다. */
    @GetMapping("/menus")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_MENU', 'MENU', 'MENU_READ')")
    public ApiResponse<PageResult<AuthorizationCatalog.MenuDefinition>> menuList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean displayed,
            @RequestParam(required = false) AdminQueryService.MenuSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(adminQueryService.menus(
                keyword, active, displayed, sort, direction, PageQuery.of(page, size)));
    }

    /** 메뉴 한 건의 계층, 경로, 정렬, 활성·표시·공용 속성을 저장한다. */
    @PostMapping("/menus/{menuId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_MENU', 'MENU', 'MENU_SAVE')")
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
                request.publicMenu(), request.area())));
    }

    /** 비활성·숨김 말단 메뉴를 삭제하고 연결된 허용 매핑을 함께 정리한다. */
    @DeleteMapping("/menus/{menuId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_MENU', 'MENU', 'MENU_DEACTIVATE')")
    public ApiResponse<Map<String, String>> deleteMenu(@PathVariable String menuId) {
        adminService.deleteMenu(menuId);
        return ApiResponse.ok(Map.of("menuId", menuId));
    }

    /** 한 권한이 메뉴에 직접 접근할 수 있는지 여부를 추가 또는 제거한다. */
    @PutMapping("/authorities/{authorityId}/menus/{menuId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_MENU', 'MENU', 'MENU_GRANT')")
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
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_READ')")
    public ApiResponse<AdminService.ProgramAdminView> programs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String menuId,
            @RequestParam(required = false) String programId) {
        return ApiResponse.ok(adminService.loadPrograms(keyword, active, menuId, programId));
    }

    /** 프로그램 마스터를 안전한 정렬과 제한된 페이지로 조회한다. */
    @GetMapping("/programs")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_READ')")
    public ApiResponse<PageResult<AuthorizationCatalog.ProgramDefinition>> programList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) AdminQueryService.ProgramSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(adminQueryService.programs(
                keyword, active, sort, direction, PageQuery.of(page, size)));
    }

    /** 프로그램 기능을 메뉴·프로그램·매핑 조건으로 검색해 제한된 페이지로 조회한다. */
    @GetMapping("/program-actions")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_READ')")
    public ApiResponse<PageResult<AuthorizationCatalog.ProgramActionDefinition>> programActionList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String menuId,
            @RequestParam(required = false) String programId,
            @RequestParam(required = false) Boolean unmapped,
            @RequestParam(required = false) AdminQueryService.ActionSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(adminQueryService.programActions(
                keyword, active, menuId, programId, unmapped, sort, direction,
                PageQuery.of(page, size)));
    }

    /** 프로그램 마스터 한 건을 신규 등록하거나 표시명·설명·활성 상태를 수정한다. */
    @PostMapping("/programs/{programId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_SAVE')")
    public ApiResponse<AuthorizationCatalog.ProgramDefinition> saveProgram(
            @PathVariable String programId,
            @Valid @RequestBody ProgramSaveRequest request) {
        return ApiResponse.ok(adminService.saveProgram(
                new AuthorizationCatalog.ProgramDefinition(
                        programId, request.name(), request.description(), request.active(), request.screen())));
    }

    /** 비활성 프로그램과 그 기능·허용 매핑을 안전하게 정리한다. */
    @DeleteMapping("/programs/{programId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_SAVE')")
    public ApiResponse<Map<String, String>> deleteProgram(@PathVariable String programId) {
        adminService.deleteProgram(programId);
        return ApiResponse.ok(Map.of("programId", programId));
    }

    /** 메뉴·프로그램 문맥에 속한 기능 한 건을 신규 등록하거나 수정한다. */
    @PostMapping("/program-actions/{menuId}/{programId}/{actionId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_ACTION_SAVE')")
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

    /** 비활성 기능을 삭제하고 모든 권한의 기능 허용 매핑을 함께 정리한다. */
    @DeleteMapping("/program-actions/{menuId}/{programId}/{actionId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_ACTION_SAVE')")
    public ApiResponse<Map<String, String>> deleteAction(
            @PathVariable String menuId,
            @PathVariable String programId,
            @PathVariable String actionId) {
        adminService.deleteAction(
                new AuthorizationCatalog.ProgramActionKey(menuId, programId, actionId));
        return ApiResponse.ok(Map.of(
                "menuId", menuId, "programId", programId, "actionId", actionId));
    }

    /** 한 권한이 특정 메뉴·프로그램 기능을 실행할 수 있는지 허용 또는 해제한다. */
    @PutMapping("/authorities/{authorityId}/program-actions/{menuId}/{programId}/{actionId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROGRAM', 'PROGRAM', 'PROGRAM_GRANT')")
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

    /** 프로젝트 마스터와 프로젝트별 참여자 전체 현황을 조회한다. */
    @GetMapping("/project-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_READ')")
    public ApiResponse<ProjectService.ProjectAdminView> projects() {
        return ApiResponse.ok(projectService.loadProjects());
    }

    /** 검색·상태·오너·활성 조건과 안전한 정렬을 적용해 프로젝트를 페이지 조회한다. */
    @GetMapping("/projects")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_READ')")
    public ApiResponse<ProjectService.ProjectSearchView> searchProjects(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Set<ProjectModels.ProjectStatus> statuses,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) ProjectModels.ProjectSort sort,
            @RequestParam(required = false) ProjectModels.SortDirection direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(projectService.searchProjects(
                new ProjectModels.ProjectSearchCriteria(
                        keyword, statuses, owner, active, sort, direction, page, size)));
    }

    /** 프로젝트 마스터·참여자·상태 이력을 한 번에 조회한다. */
    @GetMapping("/projects/{projectId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_READ')")
    public ApiResponse<ProjectService.ProjectDetail> project(@PathVariable String projectId) {
        return ApiResponse.ok(projectService.getProject(projectId));
    }

    /** 프로젝트 상태 변경 이력을 최신순으로 조회한다. */
    @GetMapping("/projects/{projectId}/status-history")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_READ')")
    public ApiResponse<java.util.List<ProjectModels.ProjectStatusHistory>> projectStatusHistory(
            @PathVariable String projectId) {
        return ApiResponse.ok(projectService.statusHistory(projectId));
    }

    /** 프로젝트 마스터를 신규 등록하거나 버전 검증 후 수정한다. */
    @PostMapping("/projects/{projectId}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_UPDATE')")
    public ApiResponse<ProjectModels.Project> saveProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectSaveRequest request) {
        return ApiResponse.ok(projectService.saveProject(
                new ProjectModels.Project(
                        projectId,
                        request.name(),
                        request.description(),
                        request.status(),
                        request.startDate(),
                        request.endDate(),
                        request.ownerUsername(),
                        request.active(),
                        request.version()),
                userContext.require().username(),
                request.statusChangeReason()));
    }

    /** 기대 버전과 사유를 확인한 뒤 프로젝트 오너를 이전한다. */
    @PostMapping("/projects/{projectId}/owner-transfer")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_OWNER_TRANSFER')")
    public ApiResponse<ProjectModels.Project> transferProjectOwner(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectOwnerTransferRequest request) {
        return ApiResponse.ok(projectService.transferOwnership(
                projectId,
                request.newOwnerUsername(),
                request.version(),
                userContext.require().username(),
                request.reason()));
    }

    /** 프로젝트를 물리 삭제하지 않고 보관 상태로 전환한다. */
    @PostMapping("/projects/{projectId}/archive")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_ARCHIVE')")
    public ApiResponse<ProjectModels.Project> archiveProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectLifecycleRequest request) {
        return ApiResponse.ok(projectService.archiveProject(
                projectId, request.version(), userContext.require().username(), request.reason()));
    }

    /** 보관된 프로젝트를 기대 버전과 사유로 복원한다. */
    @PostMapping("/projects/{projectId}/restore")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_ARCHIVE')")
    public ApiResponse<ProjectModels.Project> restoreProject(
            @PathVariable String projectId,
            @Valid @RequestBody ProjectLifecycleRequest request) {
        return ApiResponse.ok(projectService.restoreProject(
                projectId, request.version(), userContext.require().username(), request.reason()));
    }

    /** 프로젝트 참여자의 역할과 활성 상태를 등록하거나 수정한다. */
    @PutMapping("/projects/{projectId}/members/{username}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_MEMBER_MANAGE')")
    public ApiResponse<ProjectModels.ProjectMember> saveProjectMember(
            @PathVariable String projectId,
            @PathVariable String username,
            @Valid @RequestBody ProjectMemberSaveRequest request) {
        return ApiResponse.ok(projectService.saveMemberAs(
                userContext.require().username(),
                new ProjectModels.ProjectMember(
                        projectId, username, request.role(), request.active())));
    }

    /** 프로젝트 오너가 아닌 참여자를 프로젝트에서 제거한다. */
    @DeleteMapping("/projects/{projectId}/members/{username}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_PROJECT', 'PROJECT', 'PROJECT_MEMBER_MANAGE')")
    public ApiResponse<Map<String, String>> removeProjectMember(
            @PathVariable String projectId,
            @PathVariable String username) {
        projectService.removeMemberAs(userContext.require().username(), projectId, username);
        return ApiResponse.ok(Map.of("projectId", projectId, "username", username));
    }

    /** 공통코드 관리용 전체 그룹별 건수와 현재 버전을 조회한다. */
    @GetMapping("/common-code-view")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_READ')")
    public ApiResponse<CodeAdminView> commonCodes() {
        return ApiResponse.ok(new CodeAdminView(
                commonCodeService.groupSummary(),
                commonCodeService.groupVersions()));
    }

    /** 코드 그룹 메타데이터를 키워드·활성 상태로 조회한다. */
    @GetMapping("/common-code-groups")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_READ')")
    public ApiResponse<java.util.List<CommonCodeService.CommonCodeGroup>> commonCodeGroups(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.ok(commonCodeService.searchGroups(keyword, active));
    }

    /** 공통코드 그룹을 신규 등록하거나 표시·정렬·활성 정보를 수정한다. */
    @PostMapping("/common-code-groups/{groupCode}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_GROUP_SAVE')")
    public ApiResponse<CommonCodeService.CommonCodeGroup> saveCommonCodeGroup(
            @PathVariable String groupCode,
            @Valid @RequestBody CommonCodeGroupSaveRequest request) {
        return ApiResponse.ok(commonCodeService.saveGroup(
                groupCode, request.name(), request.description(),
                request.sortOrder(), request.active()));
    }

    /** 비활성·빈 사용자 정의 공통코드 그룹만 삭제한다. */
    @DeleteMapping("/common-code-groups/{groupCode}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_DELETE')")
    public ApiResponse<Map<String, String>> deleteCommonCodeGroup(
            @PathVariable String groupCode) {
        commonCodeService.deleteGroup(groupCode);
        return ApiResponse.ok(Map.of("groupCode", groupCode));
    }

    /** 한 그룹의 코드 항목을 코드명·활성·상위 코드 조건으로 조회한다. */
    @GetMapping("/common-code-groups/{groupCode}/items")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_READ')")
    public ApiResponse<java.util.List<CommonCodeService.CommonCodeItem>> commonCodeItems(
            @PathVariable String groupCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String parentCode) {
        return ApiResponse.ok(commonCodeService.searchItems(
                groupCode, keyword, active, parentCode));
    }

    /** 선택 그룹의 코드 항목을 적용 기간과 안전한 정렬을 포함해 제한된 페이지로 조회한다. */
    @GetMapping("/common-codes/{groupCode}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_READ')")
    public ApiResponse<PageResult<CommonCodeService.CommonCodeItem>> commonCodeList(
            @PathVariable String groupCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean current,
            @RequestParam(required = false) AdminQueryService.CommonCodeSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(adminQueryService.commonCodes(
                groupCode, keyword, active, current, sort, direction, PageQuery.of(page, size)));
    }

    /** 계층·정렬·활성·적용기간을 포함해 공통코드 한 건을 저장한다. */
    @PostMapping("/common-code-groups/{groupCode}/items/{code}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_SAVE')")
    public ApiResponse<CommonCodeService.CommonCodeItem> saveCommonCodeItem(
            @PathVariable String groupCode,
            @PathVariable String code,
            @Valid @RequestBody CommonCodeItemSaveRequest request) {
        return ApiResponse.ok(commonCodeService.saveItem(
                groupCode, code, request.name(), request.parentCode(), request.sortOrder(),
                request.active(), request.validFrom(), request.validTo()));
    }

    /** 최대 100개 공통코드를 전체 검증 후 한 버전으로 일괄 저장한다. */
    @PostMapping("/common-code-groups/{groupCode}/items/bulk")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_BULK_IMPORT')")
    public ApiResponse<CommonCodeService.BulkSaveResult> saveCommonCodeItemsBulk(
            @PathVariable String groupCode,
            @Valid @RequestBody CommonCodeBulkSaveRequest request) {
        return ApiResponse.ok(commonCodeService.saveItemsBulk(
                groupCode,
                request.items().stream()
                        .map(item -> new CommonCodeService.CommonCodeItem(
                                item.code(), item.name(), item.parentCode(), item.sortOrder(),
                                item.active(), item.validFrom(), item.validTo()))
                        .toList()));
    }

    /** 선택 코드와 모든 하위 코드를 같은 변경 단위로 비활성화한다. */
    @PostMapping("/common-code-groups/{groupCode}/items/{code}/deactivate")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_DEACTIVATE')")
    public ApiResponse<CommonCodeService.CommonCodeItem> deactivateCommonCodeItem(
            @PathVariable String groupCode,
            @PathVariable String code) {
        return ApiResponse.ok(commonCodeService.deactivateItem(groupCode, code));
    }

    /** 사용자 정의 그룹의 비활성 말단 코드만 삭제한다. */
    @DeleteMapping("/common-code-groups/{groupCode}/items/{code}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_DELETE')")
    public ApiResponse<Map<String, String>> deleteCommonCodeItem(
            @PathVariable String groupCode,
            @PathVariable String code) {
        commonCodeService.deleteItem(groupCode, code);
        return ApiResponse.ok(Map.of("groupCode", groupCode, "code", code));
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
            @NotNull Boolean publicMenu,
            AuthorizationCatalog.MenuArea area) {
    }

    /** 권한-메뉴 직접 허용 매핑을 추가하거나 제거하기 위한 요청이다. */
    public record MenuGrantRequest(@NotNull Boolean granted) {
    }

    /** 프로그램 마스터 표시명, 설명과 사용 가능 여부를 저장하는 요청이다. */
    public record ProgramSaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull Boolean active,
            AuthorizationCatalog.ProgramScreen screen) {
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

    /** 프로젝트 마스터의 일정, 상태, 오너와 낙관적 잠금 버전을 저장하는 요청이다. */
    public record ProjectSaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull ProjectModels.ProjectStatus status,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,100}") String ownerUsername,
            @NotNull Boolean active,
            @NotNull @Min(0) Long version,
            @Size(max = 300) String statusChangeReason) {
    }

    /** 오너 이전 대상, 낙관적 잠금 버전과 필수 업무 사유다. */
    public record ProjectOwnerTransferRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,100}") String newOwnerUsername,
            @NotNull @Min(1) Long version,
            @NotBlank @Size(max = 300) String reason) {
    }

    /** 프로젝트 보관·복원 시 사용하는 버전과 필수 업무 사유다. */
    public record ProjectLifecycleRequest(
            @NotNull @Min(1) Long version,
            @NotBlank @Size(max = 300) String reason) {
    }

    /** 프로젝트 참여자 역할과 사용 상태 변경 요청이다. */
    public record ProjectMemberSaveRequest(
            @NotNull ProjectModels.ProjectRole role,
            @NotNull Boolean active) {
    }

    /** 공통코드 그룹의 표시명·설명·정렬·활성 상태다. */
    public record CommonCodeGroupSaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 200) String description,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean active) {
    }

    /** 공통코드 한 건의 표시명·계층·정렬·활성·적용기간이다. */
    public record CommonCodeItemSaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "[A-Z0-9_]{1,30}") String parentCode,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean active,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    /** 공통코드 일괄 저장 요청은 한 번에 최대 100건으로 제한한다. */
    public record CommonCodeBulkSaveRequest(
            @NotNull @Size(min = 1, max = 100)
            List<@Valid CommonCodeBulkItemRequest> items) {
    }

    /** 일괄 저장 한 행의 코드·표시·계층·적용 조건이다. */
    public record CommonCodeBulkItemRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,30}") String code,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "[A-Z0-9_]{1,30}") String parentCode,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean active,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    /** 공통코드 그룹별 항목 수와 캐시 갱신 판단용 버전을 묶은 관리 응답이다. */
    public record CodeAdminView(Map<String, Integer> groups, Map<String, Long> versions) {
    }
}
