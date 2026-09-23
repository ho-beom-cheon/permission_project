package com.example.permissiondemo.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.MenuAuthorizationService;
import com.example.permissiondemo.authorization.MenuAuthorizationService.MenuNode;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.security.CurrentUserContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** 동일한 서버 인가 결과를 사용자 업무공간과 관리자 콘솔의 별도 응답으로 구성한다. */
@Service
public class WorkspaceService {
    private final BootstrapService bootstrap;
    private final AuthorizationCatalog catalog;
    private final MenuAuthorizationService menus;
    private final ProgramAuthorizationService programs;
    private final CurrentUserContext userContext;

    public WorkspaceService(BootstrapService bootstrap, AuthorizationCatalog catalog,
            MenuAuthorizationService menus, ProgramAuthorizationService programs,
            CurrentUserContext userContext) {
        this.bootstrap = bootstrap;
        this.catalog = catalog;
        this.menus = menus;
        this.programs = programs;
        this.userContext = userContext;
    }

    /** 메뉴 표시만으로 관리 권한을 인정하지 않고 실제 관리 기능 허용 여부를 확인한다. */
    public boolean canAccessAdmin(Authentication authentication) {
        Set<String> managementIds = managementMenuIds();
        return programs.findAllAllowedActions(authentication).stream()
                .anyMatch(action -> managementIds.contains(action.menuId()));
    }

    public BootstrapService.BootstrapView admin() {
        return scoped(bootstrap.load(List.of("USE_YN", "REGION")), true);
    }

    public PortalView portal() {
        BootstrapService.BootstrapView original = bootstrap.load(List.of("USE_YN", "REGION"));
        BootstrapService.BootstrapView context = scoped(original, false);
        Set<String> effectiveIds = Set.copyOf(context.user().effectiveAuthorityIds());
        List<AuthorityView> authorities = effectiveIds.stream().sorted()
                .map(id -> new AuthorityView(id, catalog.findAuthority(id)
                        .map(AuthorizationCatalog.AuthorityDefinition::name).orElse(id)))
                .toList();
        Map<String, AuthorizationCatalog.MenuDefinition> definitions = new HashMap<>();
        catalog.menus().forEach(menu -> definitions.put(menu.id(), menu));
        Map<String, AuthorizationCatalog.ProgramDefinition> programById = new HashMap<>();
        catalog.programs().forEach(program -> programById.put(program.id(), program));
        Set<AuthorizationCatalog.ProgramActionKey> allowed = new HashSet<>();
        context.programActions().forEach(action -> allowed.add(new AuthorizationCatalog.ProgramActionKey(
                action.menuId(), action.programId(), action.actionId())));
        List<MenuAccessView> services = new ArrayList<>();
        for (MenuNode menu : flatten(context.menus())) {
            AuthorizationCatalog.MenuDefinition menuDefinition = definitions.get(menu.id());
            // 자동 확인 도중 관리자가 메뉴를 삭제한 경우 다음 응답에서 정리하며 조회를 실패시키지 않는다.
            if (menuDefinition == null || menu.path() == null
                    || "HOME".equals(menu.id()) || "MY_AUTH_REQUEST".equals(menu.id())) {
                continue;
            }
            Map<String, List<AuthorizationCatalog.ProgramActionDefinition>> byProgram = new java.util.TreeMap<>();
            catalog.programActions().stream().filter(action -> action.menuId().equals(menu.id()))
                    .sorted(Comparator.comparingInt(AuthorizationCatalog.ProgramActionDefinition::sortOrder)
                            .thenComparing(AuthorizationCatalog.ProgramActionDefinition::actionId))
                    .forEach(action -> byProgram.computeIfAbsent(action.programId(), ignored -> new ArrayList<>()).add(action));
            List<ProgramView> linkedPrograms = new ArrayList<>();
            byProgram.forEach((id, actions) -> {
                AuthorizationCatalog.ProgramDefinition definition = programById.get(id);
                if (definition == null) {
                    return;
                }
                linkedPrograms.add(new ProgramView(id, definition.name(), definition.description(), definition.active(),
                        actions.stream().map(action -> new ActionView(action.actionId(), action.label(),
                                action.active() && definition.active(), allowed.contains(action.key()),
                                authorities.stream().filter(authority -> allowed.contains(action.key())
                                        && catalog.actionGrantsFor(authority.id()).contains(action.key())).toList()))
                                .toList(), definition.screen(), definition.screen().label(), definition.screen().path(),
                        definition.active() && definition.screen() != AuthorizationCatalog.ProgramScreen.NONE
                                && actions.stream().anyMatch(action -> allowed.contains(action.key()))
                                && (definition.screen() != AuthorizationCatalog.ProgramScreen.CONTENT_DEMO
                                    || allowed.contains(new AuthorizationCatalog.ProgramActionKey("CONTENT_LIST", "CONTENT", "CONTENT_READ")))
                                && flatten(context.menus()).stream().anyMatch(target -> target.id().equals(definition.screen().menuId())
                                        && definition.screen().path().equals(target.path()))));
            });
            services.add(new MenuAccessView(menu.id(), menu.name(), menu.path(),
                    menuDefinition.publicMenu(),
                    authorities.stream().filter(authority -> catalog.menuGrantsFor(authority.id()).contains(menu.id())).toList(),
                    List.copyOf(linkedPrograms)));
        }
        return new PortalView(context, authorities, List.copyOf(services), canAccessAdmin(userContext.authentication()));
    }

    private BootstrapService.BootstrapView scoped(BootstrapService.BootstrapView source, boolean admin) {
        Set<String> managementIds = managementMenuIds();
        List<MenuNode> tree = filterTree(source.menus(), managementIds, admin);
        Set<String> visibleIds = new HashSet<>();
        flatten(tree).stream().filter(menu -> menu.path() != null).forEach(menu -> visibleIds.add(menu.id()));
        return new BootstrapService.BootstrapView(source.user(), tree,
                source.programActions().stream().filter(action -> visibleIds.contains(action.menuId())).toList(),
                source.favorites().stream().filter(favorite -> visibleIds.contains(favorite.menuId())).toList(),
                source.codeGroups(), source.versions());
    }

    private List<MenuNode> filterTree(List<MenuNode> nodes, Set<String> managementIds, boolean admin) {
        List<MenuNode> filtered = new ArrayList<>();
        for (MenuNode node : nodes) {
            List<MenuNode> children = filterTree(node.children(), managementIds, admin);
            boolean inScope = managementIds.contains(node.id()) == admin;
            String path = inScope && menus.canAccessMenu(userContext.authentication(), node.id()) ? node.path() : null;
            if (path != null || !children.isEmpty()) {
                filtered.add(new MenuNode(node.id(), node.name(), path, children));
            }
        }
        return List.copyOf(filtered);
    }

    /** 계층 위치나 ID가 아닌 저장된 화면 영역만으로 관리 메뉴를 구분한다. */
    private Set<String> managementMenuIds() {
        Set<String> ids = new HashSet<>();
        catalog.menus().stream().filter(menu -> menu.area() == AuthorizationCatalog.MenuArea.ADMIN)
                .forEach(menu -> ids.add(menu.id()));
        return ids;
    }

    private List<MenuNode> flatten(List<MenuNode> tree) {
        List<MenuNode> flat = new ArrayList<>();
        for (MenuNode node : tree) {
            flat.add(node);
            flat.addAll(flatten(node.children()));
        }
        return flat;
    }

    public record PortalView(BootstrapService.BootstrapView context, List<AuthorityView> authorities,
            List<MenuAccessView> services, boolean adminAvailable) {}
    public record AuthorityView(String id, String name) {}
    public record MenuAccessView(String id, String name, String path, boolean publicMenu,
            List<AuthorityView> grantedBy, List<ProgramView> programs) {}
    public record ProgramView(String id, String name, String description, boolean active, List<ActionView> actions,
            AuthorizationCatalog.ProgramScreen screen, String screenName, String launchPath, boolean launchable) {}
    public record ActionView(String id, String label, boolean active, boolean allowed, List<AuthorityView> grantedBy) {}
}
