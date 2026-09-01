package com.example.permissiondemo.authorization;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.stereotype.Service;

/**
 * 권한 부여·회수와 메뉴 마스터·권한 매핑 변경을 조정하는 관리 서비스다.
 * 컨트롤러의 입력 형식과 인메모리 저장소 변경 사이에서 업무 검증과 감사 기록을 한곳에 수행한다.
 */
@Service
public class AdminService {

    private static final LocalDate DEFAULT_FROM = LocalDate.of(2020, 1, 1);
    private static final LocalDate DEFAULT_TO = LocalDate.of(2099, 12, 31);

    private final AuthorizationCatalog catalog;
    private final EffectiveAuthorityService effectiveAuthorityService;
    private final AuditEventService auditEventService;

    public AdminService(
            AuthorizationCatalog catalog,
            EffectiveAuthorityService effectiveAuthorityService,
            AuditEventService auditEventService) {
        this.catalog = catalog;
        this.effectiveAuthorityService = effectiveAuthorityService;
        this.auditEventService = auditEventService;
    }

    /** 전체 권한 마스터와 사용자별 유효 권한·부여 이력을 관리 화면 형식으로 반환한다. */
    public AuthorityAdminView loadAuthorities() {
        List<UserAuthorityView> userViews = catalog.users().stream()
                .map(user -> new UserAuthorityView(
                        user.username(),
                        user.organizationId(),
                        effectiveAuthorityService.findEffectiveAuthorityIds(user.username()).stream()
                                .sorted()
                                .toList(),
                        catalog.assignmentsFor(user.username()).stream()
                                .sorted(Comparator.comparing(
                                                AuthorizationCatalog.AuthorityAssignment::authorityId)
                                        .thenComparing(item -> item.type().name())
                                        .thenComparingLong(
                                                AuthorizationCatalog.AuthorityAssignment::sequence))
                                .toList()))
                .toList();
        return new AuthorityAdminView(catalog.authorities(), userViews, catalog.authorityVersion());
    }

    /** 권한 마스터를 신규 등록하거나 표시명·활성 상태를 수정하고 감사 이력을 남긴다. */
    public AuthorizationCatalog.AuthorityDefinition saveAuthority(
            AuthorizationCatalog.AuthorityDefinition authority) {
        boolean created = catalog.findAuthority(authority.id()).isEmpty();
        AuthorizationCatalog.AuthorityDefinition saved = catalog.saveAuthority(authority);
        auditEventService.record(
                "AUTHORITY_SAVED",
                "AUTHORITY",
                saved.id(),
                "SUCCESS",
                Map.of("changeType", created ? "CREATED" : "UPDATED"));
        return saved;
    }

    /** 직접 부여 또는 검증된 원천 사용자의 위임 권한을 승인 이력으로 추가한다. */
    public AuthorizationCatalog.AuthorityAssignment assignAuthority(
            String username,
            String authorityId,
            AuthorizationCatalog.AssignmentType type,
            LocalDate validFrom,
            LocalDate validTo,
            String delegatedBy) {
        AuthorizationCatalog.UserProfile target = catalog.findUser(username)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
        AuthorizationCatalog.AuthorityDefinition authority = catalog.findAuthority(authorityId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId));
        if (!authority.active()) {
            throw new ApiException(ErrorCode.CONFLICT, authorityId);
        }
        LocalDate resolvedFrom = validFrom == null ? DEFAULT_FROM : validFrom;
        LocalDate resolvedTo = validTo == null ? DEFAULT_TO : validTo;
        String source = validateSource(target, authority.id(), type, delegatedBy);
        AuthorizationCatalog.AuthorityAssignment saved = catalog.saveAssignment(
                target.username(), authority.id(), type, resolvedFrom, resolvedTo, source);
        auditEventService.record(
                "AUTHORITY_ASSIGNED",
                "USER_AUTHORITY",
                username + ":" + authority.id(),
                "SUCCESS",
                Map.of(
                        "assignmentType", type.name(),
                        "sequence", saved.sequence()));
        return saved;
    }

    /** 직접 또는 위임 권한의 최신 상태를 회수 이력으로 변경한다. */
    public AuthorizationCatalog.AuthorityAssignment revokeAuthority(
            String username,
            String authorityId,
            AuthorizationCatalog.AssignmentType type) {
        AuthorizationCatalog.AuthorityAssignment revoked =
                catalog.revokeAssignment(username, authorityId, type);
        auditEventService.record(
                "AUTHORITY_REVOKED",
                "USER_AUTHORITY",
                username + ":" + revoked.authorityId(),
                "SUCCESS",
                Map.of(
                        "assignmentType", type.name(),
                        "sequence", revoked.sequence()));
        return revoked;
    }

    /** 전체 메뉴 속성과 권한별 직접 허용 매핑을 관리 화면 형식으로 반환한다. */
    public MenuAdminView loadMenus() {
        return new MenuAdminView(
                catalog.menus(),
                catalog.authorities(),
                catalog.allMenuGrants(),
                catalog.menuVersion());
    }

    /** 메뉴 속성을 저장하고 변경 후 버전과 함께 감사 이력을 남긴다. */
    public AuthorizationCatalog.MenuDefinition saveMenu(
            AuthorizationCatalog.MenuDefinition menu) {
        boolean created = catalog.menus().stream().noneMatch(item -> item.id().equals(menu.id()));
        AuthorizationCatalog.MenuDefinition saved = catalog.saveMenu(menu);
        auditEventService.record(
                "MENU_SAVED",
                "MENU",
                saved.id(),
                "SUCCESS",
                Map.of("changeType", created ? "CREATED" : "UPDATED"));
        return saved;
    }

    /** 권한-메뉴 직접 허용 여부를 저장하고 변경된 권한의 최종 메뉴 집합을 반환한다. */
    public Set<String> setMenuGrant(String authorityId, String menuId, boolean granted) {
        Set<String> grants = catalog.setMenuGrant(authorityId, menuId, granted);
        auditEventService.record(
                "MENU_GRANT_CHANGED",
                "AUTHORITY_MENU",
                authorityId + ":" + menuId,
                "SUCCESS",
                Map.of("granted", granted));
        return grants;
    }

    /** 프로그램·기능과 권한별 기능 매핑의 전체 관리 원본을 반환한다. */
    public ProgramAdminView loadPrograms() {
        return new ProgramAdminView(
                catalog.programs(),
                catalog.programActions(),
                catalog.authorities(),
                catalog.menus(),
                catalog.allActionGrants(),
                catalog.programVersion());
    }

    /** 프로그램 마스터를 신규 등록하거나 수정하고 감사 이력을 남긴다. */
    public AuthorizationCatalog.ProgramDefinition saveProgram(
            AuthorizationCatalog.ProgramDefinition program) {
        boolean created = catalog.programs().stream()
                .noneMatch(item -> item.id().equals(program.id()));
        AuthorizationCatalog.ProgramDefinition saved = catalog.saveProgram(program);
        auditEventService.record(
                "PROGRAM_SAVED",
                "PROGRAM",
                saved.id(),
                "SUCCESS",
                Map.of("changeType", created ? "CREATED" : "UPDATED"));
        return saved;
    }

    /** 프로그램 기능을 신규 등록하거나 수정하고 메뉴·프로그램 복합 키로 감사 이력을 남긴다. */
    public AuthorizationCatalog.ProgramActionDefinition saveAction(
            AuthorizationCatalog.ProgramActionDefinition action) {
        boolean created = catalog.programActions().stream()
                .noneMatch(item -> item.key().equals(action.key()));
        AuthorizationCatalog.ProgramActionDefinition saved = catalog.saveAction(action);
        auditEventService.record(
                "PROGRAM_ACTION_SAVED",
                "PROGRAM_ACTION",
                saved.menuId() + ":" + saved.programId() + ":" + saved.actionId(),
                "SUCCESS",
                Map.of("changeType", created ? "CREATED" : "UPDATED"));
        return saved;
    }

    /** 권한-프로그램 기능 허용 여부를 저장하고 변경된 권한의 최종 기능 집합을 반환한다. */
    public Set<AuthorizationCatalog.ProgramActionKey> setActionGrant(
            String authorityId,
            AuthorizationCatalog.ProgramActionKey actionKey,
            boolean granted) {
        Set<AuthorizationCatalog.ProgramActionKey> grants =
                catalog.setActionGrant(authorityId, actionKey, granted);
        auditEventService.record(
                "PROGRAM_GRANT_CHANGED",
                "AUTHORITY_ACTION",
                authorityId + ":" + actionKey.menuId() + ":"
                        + actionKey.programId() + ":" + actionKey.actionId(),
                "SUCCESS",
                Map.of("granted", granted));
        return grants;
    }

    /** 위임인 경우 원천 사용자·조직·직접 권한을 확인하고 저장할 사용자명을 반환한다. */
    private String validateSource(
            AuthorizationCatalog.UserProfile target,
            String authorityId,
            AuthorizationCatalog.AssignmentType type,
            String delegatedBy) {
        if (type == AuthorizationCatalog.AssignmentType.DIRECT) {
            return null;
        }
        if (delegatedBy == null || delegatedBy.isBlank() || delegatedBy.equals(target.username())) {
            throw new IllegalArgumentException("위임 권한은 대상과 다른 원천 사용자가 필요합니다.");
        }
        AuthorizationCatalog.UserProfile source = catalog.findUser(delegatedBy)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, delegatedBy));
        if (!source.organizationId().equals(target.organizationId())) {
            throw new ApiException(ErrorCode.CONFLICT, delegatedBy);
        }
        if (!effectiveAuthorityService.hasDirectAuthority(source.username(), authorityId)) {
            throw new ApiException(ErrorCode.CONFLICT, delegatedBy);
        }
        return source.username();
    }

    /** 권한 마스터, 사용자별 계산 결과와 현재 권한 버전을 묶은 관리 응답이다. */
    public record AuthorityAdminView(
            List<AuthorizationCatalog.AuthorityDefinition> authorities,
            List<UserAuthorityView> users,
            long version) {
    }

    /** 사용자 한 명의 현재 조직, 유효 권한과 전체 변경 이력이다. */
    public record UserAuthorityView(
            String username,
            String organizationId,
            List<String> effectiveAuthorityIds,
            List<AuthorizationCatalog.AuthorityAssignment> assignments) {
    }

    /** 전체 메뉴, 권한 마스터, 권한별 메뉴 매핑과 현재 메뉴 버전을 묶은 관리 응답이다. */
    public record MenuAdminView(
            List<AuthorizationCatalog.MenuDefinition> menus,
            List<AuthorizationCatalog.AuthorityDefinition> authorities,
            Map<String, Set<String>> grants,
            long version) {
    }

    /** 프로그램·기능·권한 매핑과 현재 프로그램 버전을 묶은 관리 응답이다. */
    public record ProgramAdminView(
            List<AuthorizationCatalog.ProgramDefinition> programs,
            List<AuthorizationCatalog.ProgramActionDefinition> actions,
            List<AuthorizationCatalog.AuthorityDefinition> authorities,
            List<AuthorizationCatalog.MenuDefinition> menus,
            Map<String, Set<AuthorizationCatalog.ProgramActionKey>> grants,
            long version) {
    }
}
