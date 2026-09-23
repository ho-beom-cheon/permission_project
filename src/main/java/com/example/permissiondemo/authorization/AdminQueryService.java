package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.SortDirection;
import org.springframework.stereotype.Service;

/** 관리자 표에서 사용하는 검색, 필터, 정렬과 제한 페이징을 한곳에서 처리한다. */
@Service
public class AdminQueryService {

    private final AdminService adminService;
    private final CommonCodeService commonCodeService;
    private final Clock clock;

    public AdminQueryService(
            AdminService adminService,
            CommonCodeService commonCodeService,
            Clock clock) {
        this.adminService = adminService;
        this.commonCodeService = commonCodeService;
        this.clock = clock;
    }

    public PageResult<AuthorizationCatalog.AuthorityDefinition> authorities(
            String keyword,
            Boolean active,
            AuthoritySort sort,
            SortDirection direction,
            PageQuery page) {
        Comparator<AuthorizationCatalog.AuthorityDefinition> comparator = switch (value(sort, AuthoritySort.ID)) {
            case ID -> Comparator.comparing(AuthorizationCatalog.AuthorityDefinition::id);
            case NAME -> Comparator.comparing(AuthorizationCatalog.AuthorityDefinition::name);
            case ACTIVE -> Comparator.comparing(AuthorizationCatalog.AuthorityDefinition::active)
                    .thenComparing(AuthorizationCatalog.AuthorityDefinition::id);
        };
        return page(adminService.loadAuthorities(keyword, null, active).authorities(), comparator, direction, page);
    }

    public PageResult<AdminService.UserAuthorityView> userAuthorities(
            String keyword,
            String organizationId,
            AuthorizationCatalog.AssignmentType assignmentType,
            Boolean revoked,
            UserAuthoritySort sort,
            SortDirection direction,
            PageQuery page) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<AdminService.UserAuthorityView> users = adminService
                .loadAuthorities(null, organizationId, null).users().stream()
                .filter(user -> normalizedKeyword.isEmpty()
                        || user.username().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || user.organizationId().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || user.effectiveAuthorityIds().stream()
                                .anyMatch(id -> id.toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                        || user.assignments().stream().anyMatch(item ->
                                item.authorityId().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                                || item.type().name().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                                || item.status().name().toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
                .filter(user -> assignmentType == null || user.assignments().stream()
                        .anyMatch(item -> item.type() == assignmentType))
                .filter(user -> revoked == null || user.assignments().stream()
                        .anyMatch(item -> (item.status() == AuthorizationCatalog.AssignmentStatus.REVOKED) == revoked))
                .toList();
        Comparator<AdminService.UserAuthorityView> comparator = switch (value(sort, UserAuthoritySort.USERNAME)) {
            case USERNAME -> Comparator.comparing(AdminService.UserAuthorityView::username);
            case ORGANIZATION -> Comparator.comparing(AdminService.UserAuthorityView::organizationId)
                    .thenComparing(AdminService.UserAuthorityView::username);
            case EFFECTIVE_AUTHORITY_COUNT -> Comparator
                    .comparingInt((AdminService.UserAuthorityView user) -> user.effectiveAuthorityIds().size())
                    .thenComparing(AdminService.UserAuthorityView::username);
        };
        return page(users, comparator, direction, page);
    }

    public PageResult<AuthorizationCatalog.MenuDefinition> menus(
            String keyword,
            Boolean active,
            Boolean displayed,
            MenuSort sort,
            SortDirection direction,
            PageQuery page) {
        Comparator<AuthorizationCatalog.MenuDefinition> comparator = switch (value(sort, MenuSort.ORDER)) {
            case ID -> Comparator.comparing(AuthorizationCatalog.MenuDefinition::id);
            case NAME -> Comparator.comparing(AuthorizationCatalog.MenuDefinition::name);
            case ORDER -> Comparator.comparingInt(AuthorizationCatalog.MenuDefinition::sortOrder)
                    .thenComparing(AuthorizationCatalog.MenuDefinition::id);
            case PARENT -> Comparator.comparing(
                            AuthorizationCatalog.MenuDefinition::parentId,
                            Comparator.nullsFirst(String::compareTo))
                    .thenComparingInt(AuthorizationCatalog.MenuDefinition::sortOrder);
        };
        return page(adminService.loadMenus(keyword, active, displayed).menus(), comparator, direction, page);
    }

    public PageResult<AuthorizationCatalog.ProgramDefinition> programs(
            String keyword,
            Boolean active,
            ProgramSort sort,
            SortDirection direction,
            PageQuery page) {
        Comparator<AuthorizationCatalog.ProgramDefinition> comparator = switch (value(sort, ProgramSort.ID)) {
            case ID -> Comparator.comparing(AuthorizationCatalog.ProgramDefinition::id);
            case NAME -> Comparator.comparing(AuthorizationCatalog.ProgramDefinition::name);
            case ACTIVE -> Comparator.comparing(AuthorizationCatalog.ProgramDefinition::active)
                    .thenComparing(AuthorizationCatalog.ProgramDefinition::id);
        };
        return page(adminService.loadPrograms(keyword, active, null, null).programs(), comparator, direction, page);
    }

    public PageResult<AuthorizationCatalog.ProgramActionDefinition> programActions(
            String keyword,
            Boolean active,
            String menuId,
            String programId,
            Boolean unmapped,
            ActionSort sort,
            SortDirection direction,
            PageQuery page) {
        AdminService.ProgramAdminView view = adminService.loadPrograms(keyword, active, menuId, programId);
        List<AuthorizationCatalog.ProgramActionDefinition> actions = view.actions().stream()
                .filter(action -> unmapped == null || !unmapped || view.grants().values().stream()
                        .noneMatch(grants -> grants.contains(action.key())))
                .toList();
        Comparator<AuthorizationCatalog.ProgramActionDefinition> comparator = switch (value(sort, ActionSort.ORDER)) {
            case MENU -> Comparator.comparing(AuthorizationCatalog.ProgramActionDefinition::menuId)
                    .thenComparing(AuthorizationCatalog.ProgramActionDefinition::programId)
                    .thenComparing(AuthorizationCatalog.ProgramActionDefinition::actionId);
            case PROGRAM -> Comparator.comparing(AuthorizationCatalog.ProgramActionDefinition::programId)
                    .thenComparing(AuthorizationCatalog.ProgramActionDefinition::actionId);
            case ACTION -> Comparator.comparing(AuthorizationCatalog.ProgramActionDefinition::actionId);
            case ORDER -> Comparator.comparingInt(AuthorizationCatalog.ProgramActionDefinition::sortOrder)
                    .thenComparing(AuthorizationCatalog.ProgramActionDefinition::actionId);
        };
        return page(actions, comparator, direction, page);
    }

    public PageResult<CommonCodeService.CommonCodeItem> commonCodes(
            String groupCode,
            String keyword,
            Boolean active,
            Boolean current,
            CommonCodeSort sort,
            SortDirection direction,
            PageQuery page) {
        LocalDate today = LocalDate.now(clock);
        List<CommonCodeService.CommonCodeItem> items = commonCodeService
                .searchItems(groupCode, keyword, active, null).stream()
                .filter(item -> current == null || isCurrent(item, today) == current)
                .toList();
        Comparator<CommonCodeService.CommonCodeItem> comparator = switch (value(sort, CommonCodeSort.ORDER)) {
            case CODE -> Comparator.comparing(CommonCodeService.CommonCodeItem::code);
            case NAME -> Comparator.comparing(CommonCodeService.CommonCodeItem::name);
            case ORDER -> Comparator.comparingInt(CommonCodeService.CommonCodeItem::sortOrder)
                    .thenComparing(CommonCodeService.CommonCodeItem::code);
            case VALID_FROM -> Comparator.comparing(CommonCodeService.CommonCodeItem::validFrom)
                    .thenComparing(CommonCodeService.CommonCodeItem::code);
        };
        return page(items, comparator, direction, page);
    }

    private boolean isCurrent(CommonCodeService.CommonCodeItem item, LocalDate today) {
        return item.active() && !today.isBefore(item.validFrom()) && !today.isAfter(item.validTo());
    }

    private <T> PageResult<T> page(
            List<T> source,
            Comparator<T> comparator,
            SortDirection direction,
            PageQuery page) {
        Comparator<T> resolved = value(direction, SortDirection.ASC) == SortDirection.DESC
                ? comparator.reversed() : comparator;
        return PageResult.of(source.stream().sorted(resolved).toList(), page);
    }

    private <T> T value(T requested, T fallback) {
        return requested == null ? fallback : requested;
    }

    public enum AuthoritySort { ID, NAME, ACTIVE }
    public enum UserAuthoritySort { USERNAME, ORGANIZATION, EFFECTIVE_AUTHORITY_COUNT }
    public enum MenuSort { ID, NAME, ORDER, PARENT }
    public enum ProgramSort { ID, NAME, ACTIVE }
    public enum ActionSort { MENU, PROGRAM, ACTION, ORDER }
    public enum CommonCodeSort { CODE, NAME, ORDER, VALID_FROM }
}
