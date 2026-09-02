package com.example.permissiondemo.authorization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 공용 메뉴와 사용자의 유효 권한에 연결된 메뉴를 합쳐 화면용 계층 트리를 만든다.
 * 메뉴 표시 여부와 실제 접근 가능 여부를 분리해 상위 컨테이너 노출이 권한 상승으로 이어지지 않게 한다.
 */
@Service
@com.example.permissiondemo.storage.StateBoundary
public class MenuAuthorizationService {

    private final AuthorizationCatalog catalog;
    private final EffectiveAuthorityService effectiveAuthorityService;

    public MenuAuthorizationService(
            AuthorizationCatalog catalog,
            EffectiveAuthorityService effectiveAuthorityService) {
        this.catalog = catalog;
        this.effectiveAuthorityService = effectiveAuthorityService;
    }

    /**
     * 현재 사용자가 볼 수 있는 메뉴만 계층 구조로 반환한다.
     * 직접 허용된 하위 메뉴의 상위 노드는 탐색을 위해 포함하지만 접근 권한으로 간주하지 않는다.
     */
    public List<MenuNode> findAuthorizedMenuTree(Authentication authentication) {
        Map<String, AuthorizationCatalog.MenuDefinition> menuById = catalog.menus().stream()
                .collect(Collectors.toMap(AuthorizationCatalog.MenuDefinition::id, Function.identity()));

        // 실제 접근 가능한 메뉴 중 화면에 표시할 수 없는 비활성·숨김 메뉴를 트리 대상에서 제외한다.
        Set<String> directlyAllowedIds = new LinkedHashSet<>(findAccessibleMenuIds(authentication));
        directlyAllowedIds.removeIf(menuId -> menuById.get(menuId) == null
                || !isVisibleAndActive(menuById.get(menuId)));
        // 허용된 하위 메뉴까지 사용자가 탐색할 수 있도록 표시 가능한 조상 컨테이너를 보존한다.
        Set<String> includedIds = new LinkedHashSet<>(directlyAllowedIds);
        directlyAllowedIds.forEach(menuId -> includeAncestors(menuId, menuById, includedIds));

        // 재귀 트리 생성을 위해 포함 대상 메뉴를 부모 ID별로 미리 묶는다.
        Map<String, List<AuthorizationCatalog.MenuDefinition>> childrenByParent = catalog.menus().stream()
                .filter(this::isVisibleAndActive)
                .filter(menu -> includedIds.contains(menu.id()))
                .filter(menu -> menu.parentId() != null)
                .collect(Collectors.groupingBy(AuthorizationCatalog.MenuDefinition::parentId));

        return catalog.menus().stream()
                .filter(this::isVisibleAndActive)
                .filter(menu -> includedIds.contains(menu.id()))
                .filter(menu -> menu.parentId() == null)
                .sorted(Comparator.comparingInt(AuthorizationCatalog.MenuDefinition::sortOrder))
                .map(menu -> buildNode(menu, childrenByParent, new HashSet<>()))
                .toList();
    }

    /**
     * 상위 컨테이너가 보이는 것과 해당 메뉴 기능을 실행할 수 있는 것은 구분한다.
     * URL 직접 호출과 관심 메뉴 등록에서도 이 판정을 다시 사용한다.
     */
    public boolean canAccessMenu(Authentication authentication, String menuId) {
        return findAccessibleMenuIds(authentication).contains(menuId);
    }

    /** 공용 메뉴와 유효 권한별 메뉴 허용 목록을 합쳐 실제 접근 가능한 ID 집합을 만든다. */
    private Set<String> findAccessibleMenuIds(Authentication authentication) {
        Set<String> allowedIds = catalog.menus().stream()
                .filter(AuthorizationCatalog.MenuDefinition::active)
                .filter(AuthorizationCatalog.MenuDefinition::publicMenu)
                .map(AuthorizationCatalog.MenuDefinition::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        effectiveAuthorityService.findEffectiveAuthorityIds(authentication).stream()
                .flatMap(authorityId -> catalog.menuGrantsFor(authorityId).stream())
                .forEach(allowedIds::add);

        allowedIds.removeIf(menuId -> catalog.menus().stream()
                .filter(menu -> menu.id().equals(menuId))
                .findFirst()
                .map(menu -> !menu.active())
                .orElse(true));
        return allowedIds;
    }

    /**
     * 허용된 메뉴에서 루트까지 올라가며 표시 가능한 조상 메뉴를 결과 집합에 추가한다.
     * 잘못된 데이터의 순환 참조는 무한 반복 대신 즉시 예외로 중단한다.
     */
    private void includeAncestors(
            String menuId,
            Map<String, AuthorizationCatalog.MenuDefinition> menuById,
            Set<String> includedIds) {
        Set<String> visited = new HashSet<>();
        AuthorizationCatalog.MenuDefinition current = menuById.get(menuId);

        while (current != null && current.parentId() != null) {
            if (!visited.add(current.id())) {
                throw new IllegalStateException("메뉴 순환 참조가 감지되었습니다: " + current.id());
            }
            AuthorizationCatalog.MenuDefinition parent = menuById.get(current.parentId());
            if (parent == null || !isVisibleAndActive(parent)) {
                return;
            }
            includedIds.add(parent.id());
            current = parent;
        }
    }

    /** 부모별 자식 목록을 정렬하면서 불변 화면 트리 노드로 변환한다. */
    private MenuNode buildNode(
            AuthorizationCatalog.MenuDefinition menu,
            Map<String, List<AuthorizationCatalog.MenuDefinition>> childrenByParent,
            Set<String> visiting) {
        if (!visiting.add(menu.id())) {
            throw new IllegalStateException("메뉴 순환 참조가 감지되었습니다: " + menu.id());
        }

        List<MenuNode> children = new ArrayList<>();
        childrenByParent.getOrDefault(menu.id(), List.of()).stream()
                .sorted(Comparator.comparingInt(AuthorizationCatalog.MenuDefinition::sortOrder))
                .map(child -> buildNode(child, childrenByParent, visiting))
                .forEach(children::add);

        visiting.remove(menu.id());
        return new MenuNode(menu.id(), menu.name(), menu.path(), List.copyOf(children));
    }

    /** 메뉴 트리 노출에 필요한 활성 상태와 displayed 플래그를 함께 검사한다. */
    private boolean isVisibleAndActive(AuthorizationCatalog.MenuDefinition menu) {
        return menu.active() && menu.displayed();
    }

    /** 클라이언트가 렌더링하는 최소 메뉴 정보와 하위 노드 목록이다. */
    public record MenuNode(String id, String name, String path, List<MenuNode> children) {
    }
}
