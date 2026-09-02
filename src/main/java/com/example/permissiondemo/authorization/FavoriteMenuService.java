package com.example.permissiondemo.authorization;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.stereotype.Service;

/**
 * 사용자별 관심 메뉴와 변경 버전을 관리하며 로컬 저장 상태에 참여한다.
 * 관심 메뉴는 편의 정보일 뿐이므로 등록과 조회 시 현재 메뉴 접근 권한을 항상 다시 검사한다.
 */
@Service
@com.example.permissiondemo.storage.StateBoundary
public class FavoriteMenuService implements com.example.permissiondemo.storage.StateParticipant {

    private static final Pattern MENU_PATTERN = Pattern.compile("[A-Z0-9_]{1,40}");

    private final ConcurrentMap<String, Set<String>> favorites = new ConcurrentHashMap<>();
    private final CurrentUserContext userContext;
    private final AuthorizationCatalog catalog;
    private final MenuAuthorizationService menuAuthorizationService;
    private final AuditEventService auditEventService;

    public FavoriteMenuService(
            CurrentUserContext userContext,
            AuthorizationCatalog catalog,
            MenuAuthorizationService menuAuthorizationService,
            AuditEventService auditEventService) {
        this.userContext = userContext;
        this.catalog = catalog;
        this.menuAuthorizationService = menuAuthorizationService;
        this.auditEventService = auditEventService;
    }

    /**
     * 저장된 관심 메뉴와 현재 접근 가능한 표시 메뉴의 교집합만 반환한다.
     * 권한이 회수되거나 메뉴가 비활성화되면 저장값이 남아 있어도 응답에서는 제외된다.
     */
    public List<FavoriteMenu> findFavorites() {
        CurrentUserContext.CurrentUser user = userContext.require();
        Set<String> stored = favorites.getOrDefault(user.username(), Set.of());
        return catalog.menus().stream()
                .filter(menu -> stored.contains(menu.id()))
                .filter(this::isVisibleLeaf)
                .filter(menu -> menuAuthorizationService.canAccessMenu(
                        userContext.authentication(), menu.id()))
                .sorted(Comparator.comparingInt(AuthorizationCatalog.MenuDefinition::sortOrder))
                .map(menu -> new FavoriteMenu(menu.id(), menu.name(), menu.path()))
                .toList();
    }

    /**
     * 현재 사용자가 실제 접근할 수 있는 화면 메뉴만 관심 메뉴로 등록한다.
     * 상위 컨테이너와 숨김 메뉴는 등록할 수 없으며 성공 이벤트를 감사 이력에 남긴다.
     */
    public FavoriteMenu addFavorite(String menuId) {
        String normalizedMenuId = normalizeMenuId(menuId);
        CurrentUserContext.CurrentUser user = userContext.require();
        AuthorizationCatalog.MenuDefinition menu = catalog.menus().stream()
                .filter(candidate -> candidate.id().equals(normalizedMenuId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!isVisibleLeaf(menu)
                || !menuAuthorizationService.canAccessMenu(
                        userContext.authentication(), normalizedMenuId)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        favorites.computeIfAbsent(user.username(), ignored -> ConcurrentHashMap.newKeySet())
                .add(normalizedMenuId);
        auditEventService.record(
                "FAVORITE_MENU_ADDED",
                "MENU",
                normalizedMenuId,
                "SUCCESS",
                Map.of());
        return new FavoriteMenu(menu.id(), menu.name(), menu.path());
    }

    /** 사용자 자신의 관심 메뉴에서 지정 메뉴를 제거하고 감사 이벤트를 기록한다. */
    public void removeFavorite(String menuId) {
        String normalizedMenuId = normalizeMenuId(menuId);
        CurrentUserContext.CurrentUser user = userContext.require();
        Set<String> stored = favorites.get(user.username());
        if (stored != null) {
            stored.remove(normalizedMenuId);
        }
        auditEventService.record(
                "FAVORITE_MENU_REMOVED",
                "MENU",
                normalizedMenuId,
                "SUCCESS",
                Map.of());
    }

    /** 활성·표시 상태이고 실제 화면 경로가 있는 메뉴인지 확인한다. */
    private boolean isVisibleLeaf(AuthorizationCatalog.MenuDefinition menu) {
        return menu.active() && menu.displayed() && menu.path() != null;
    }

    /** 경로 변수의 메뉴 ID를 대문자로 정규화하고 허용 문자와 길이를 검증한다. */
    private String normalizeMenuId(String menuId) {
        if (menuId == null) {
            throw new IllegalArgumentException("menuId는 필수입니다.");
        }
        String normalized = menuId.trim().toUpperCase(Locale.ROOT);
        if (!MENU_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("menuId 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    /** 관심 메뉴 화면에 필요한 식별자, 표시명과 이동 경로다. */
    public record FavoriteMenu(String menuId, String name, String path) {
    }

    @Override public String stateKey() { return "favorites"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() {
        Map<String, Set<String>> values = new java.util.TreeMap<>();
        favorites.forEach((key, value) -> values.put(key, Set.copyOf(value)));
        return new StoredState(values);
    }
    @Override public void restoreState(Object raw) {
        favorites.clear(); ((StoredState) raw).favorites().forEach((key, value) -> {
            Set<String> items = ConcurrentHashMap.newKeySet(); items.addAll(value); favorites.put(key, items);
        });
    }
    public record StoredState(Map<String, Set<String>> favorites) { }
}
