package com.example.permissiondemo.authorization;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 메뉴·프로그램·기능 ID의 세 가지 문맥으로 세부 기능 권한을 판정한다.
 * 화면 버튼 제어용 목록과 서버 {@code @PreAuthorize} 판정이 같은 기준 데이터를 사용한다.
 */
@Service
@com.example.permissiondemo.storage.StateBoundary
public class ProgramAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(ProgramAuthorizationService.class);

    private final AuthorizationCatalog catalog;
    private final EffectiveAuthorityService effectiveAuthorityService;
    private final MenuAuthorizationService menuAuthorizationService;

    public ProgramAuthorizationService(
            AuthorizationCatalog catalog,
            EffectiveAuthorityService effectiveAuthorityService,
            MenuAuthorizationService menuAuthorizationService) {
        this.catalog = catalog;
        this.effectiveAuthorityService = effectiveAuthorityService;
        this.menuAuthorizationService = menuAuthorizationService;
    }

    /**
     * 한 메뉴·프로그램에서 현재 사용자에게 허용된 기능을 화면 표시 순서로 반환한다.
     * 빈 문맥은 예외 대신 빈 목록으로 처리해 권한 정보 누락 시 버튼이 활성화되지 않게 한다.
     */
    public List<ActionPermission> findAllowedActions(
            Authentication authentication, String menuId, String programId) {
        if (menuId == null || menuId.isBlank() || programId == null || programId.isBlank()) {
            return List.of();
        }
        String normalizedMenuId = menuId.trim().toUpperCase(Locale.ROOT);
        String normalizedProgramId = programId.trim().toUpperCase(Locale.ROOT);
        Set<AuthorizationCatalog.ProgramActionKey> grantedActions =
                findGrantedActions(authentication);

        return catalog.programActions().stream()
                .filter(AuthorizationCatalog.ProgramActionDefinition::active)
                .filter(action -> catalog.isProgramActive(action.programId()))
                .filter(action -> action.menuId().equals(normalizedMenuId))
                .filter(action -> action.programId().equals(normalizedProgramId))
                .filter(action -> menuAuthorizationService.canAccessMenu(authentication, action.menuId()))
                .filter(action -> grantedActions.contains(action.key()))
                .sorted(Comparator.comparingInt(AuthorizationCatalog.ProgramActionDefinition::sortOrder))
                .map(action -> new ActionPermission(
                        action.menuId(),
                        action.programId(),
                        action.actionId(),
                        action.label(),
                        action.componentId()))
                .toList();
    }

    /**
     * 현재 사용자의 모든 접근 가능 메뉴에서 허용된 기능을 반환한다.
     * Bootstrap 응답이 화면별 추가 요청 없이 초기 버튼 상태를 구성할 때 사용한다.
     */
    public List<ActionPermission> findAllAllowedActions(Authentication authentication) {
        Set<AuthorizationCatalog.ProgramActionKey> grantedActions =
                findGrantedActions(authentication);
        return catalog.programActions().stream()
                .filter(AuthorizationCatalog.ProgramActionDefinition::active)
                .filter(action -> catalog.isProgramActive(action.programId()))
                .filter(action -> menuAuthorizationService.canAccessMenu(
                        authentication, action.menuId()))
                .filter(action -> grantedActions.contains(action.key()))
                .sorted(Comparator
                        .comparing(AuthorizationCatalog.ProgramActionDefinition::menuId)
                        .thenComparing(AuthorizationCatalog.ProgramActionDefinition::programId)
                        .thenComparingInt(AuthorizationCatalog.ProgramActionDefinition::sortOrder))
                .map(action -> new ActionPermission(
                        action.menuId(),
                        action.programId(),
                        action.actionId(),
                        action.label(),
                        action.componentId()))
                .toList();
    }

    /**
     * Spring Method Security에서 호출하는 fail-closed 권한 판정 메서드다.
     * 기능 정의의 활성 여부, 메뉴 접근 가능 여부, 권한-기능 매핑을 모두 통과해야 true다.
     */
    public boolean isAllowed(
            Authentication authentication,
            String menuId,
            String programId,
            String actionId) {
        if (menuId == null || menuId.isBlank()
                || programId == null || programId.isBlank()
                || actionId == null || actionId.isBlank()) {
            return false;
        }
        String normalizedMenuId = menuId.trim().toUpperCase(Locale.ROOT);
        String normalizedProgramId = programId.trim().toUpperCase(Locale.ROOT);
        String normalizedActionId = actionId.trim().toUpperCase(Locale.ROOT);
        AuthorizationCatalog.ProgramActionKey requestedKey =
                new AuthorizationCatalog.ProgramActionKey(
                        normalizedMenuId, normalizedProgramId, normalizedActionId);

        return catalog.programActions().stream()
                .filter(AuthorizationCatalog.ProgramActionDefinition::active)
                .filter(action -> catalog.isProgramActive(action.programId()))
                .filter(action -> action.key().equals(requestedKey))
                .anyMatch(action -> menuAuthorizationService.canAccessMenu(authentication, action.menuId())
                        && findGrantedActions(authentication).contains(requestedKey));
    }

    /**
     * 서비스 내부에서 명시적인 기능 권한 확인이 필요할 때 사용한다.
     * 거부 사실을 로그로 남긴 뒤 Spring Security 표준 AccessDeniedException을 발생시킨다.
     */
    public void requireAction(
            Authentication authentication,
            String menuId,
            String programId,
            String actionId) {
        if (!isAllowed(authentication, menuId, programId, actionId)) {
            String username = authentication == null ? "anonymous" : authentication.getName();
            log.warn("Authorization denied: user={}, menu={}, program={}, action={}",
                    username, menuId, programId, actionId);
            throw new AccessDeniedException("요청한 기능을 실행할 권한이 없습니다.");
        }
    }

    /** 사용자의 모든 유효 업무 권한에 연결된 기능 키를 중복 없이 합친다. */
    private Set<AuthorizationCatalog.ProgramActionKey> findGrantedActions(
            Authentication authentication) {
        Set<AuthorizationCatalog.ProgramActionKey> grantedActions = new HashSet<>();
        effectiveAuthorityService.findEffectiveAuthorityIds(authentication).stream()
                .flatMap(authorityId -> catalog.actionGrantsFor(authorityId).stream())
                .forEach(grantedActions::add);
        return grantedActions;
    }

    /** 화면이 버튼과 기능을 연결하는 데 사용하는 허용 기능 응답이다. */
    public record ActionPermission(
            String menuId,
            String programId,
            String actionId,
            String label,
            String componentId) {
    }
}
