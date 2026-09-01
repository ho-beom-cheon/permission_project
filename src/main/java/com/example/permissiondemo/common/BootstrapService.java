package com.example.permissiondemo.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.EffectiveAuthorityService;
import com.example.permissiondemo.authorization.FavoriteMenuService;
import com.example.permissiondemo.authorization.MenuAuthorizationService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.security.CurrentUserContext;

import org.springframework.stereotype.Service;

/**
 * 첫 화면에 필요한 사용자·메뉴·기능·관심 메뉴·공통코드를 하나의 일관된 응답으로 조립한다.
 * 클라이언트가 여러 API를 순차 호출하면서 서로 다른 시점의 권한 상태를 받는 문제를 줄인다.
 */
@Service
public class BootstrapService {

    private static final int MAX_CODE_GROUPS = 10;

    private final CurrentUserContext userContext;
    private final AuthorizationCatalog catalog;
    private final EffectiveAuthorityService effectiveAuthorityService;
    private final MenuAuthorizationService menuAuthorizationService;
    private final ProgramAuthorizationService programAuthorizationService;
    private final FavoriteMenuService favoriteMenuService;
    private final CommonCodeService commonCodeService;

    public BootstrapService(
            CurrentUserContext userContext,
            AuthorizationCatalog catalog,
            EffectiveAuthorityService effectiveAuthorityService,
            MenuAuthorizationService menuAuthorizationService,
            ProgramAuthorizationService programAuthorizationService,
            FavoriteMenuService favoriteMenuService,
            CommonCodeService commonCodeService) {
        this.userContext = userContext;
        this.catalog = catalog;
        this.effectiveAuthorityService = effectiveAuthorityService;
        this.menuAuthorizationService = menuAuthorizationService;
        this.programAuthorizationService = programAuthorizationService;
        this.favoriteMenuService = favoriteMenuService;
        this.commonCodeService = commonCodeService;
    }

    /**
     * 현재 인증 사용자를 기준으로 초기 화면 스냅샷을 구성한다.
     * 과도한 일괄 코드 조회를 막기 위해 요청 가능한 공통코드 그룹 수를 10개로 제한한다.
     */
    public BootstrapView load(List<String> codeGroups) {
        if (codeGroups.size() > MAX_CODE_GROUPS) {
            throw new IllegalArgumentException("공통코드 그룹은 한 번에 10개까지 요청할 수 있습니다.");
        }
        // 사용자 정보는 요청 파라미터가 아닌 보안 컨텍스트에서만 가져온다.
        CurrentUserContext.CurrentUser current = userContext.require();
        Map<String, CommonCodeService.CodeGroupView> codes = new LinkedHashMap<>();
        // 중복 그룹은 한 번만 조회하고 서비스가 정규화한 그룹 코드를 응답 키로 사용한다.
        codeGroups.stream().distinct().forEach(groupCode -> {
            CommonCodeService.CodeGroupView groupView =
                    commonCodeService.findGroupView(groupCode, true, null);
            codes.put(groupView.groupCode(), groupView);
        });

        return new BootstrapView(
                new UserView(
                        current.username(),
                        current.organizationId(),
                        effectiveAuthorityService.findEffectiveAuthorityIds(
                                        userContext.authentication()).stream()
                                .sorted()
                                .toList()),
                menuAuthorizationService.findAuthorizedMenuTree(
                        userContext.authentication()),
                programAuthorizationService.findAllAllowedActions(
                        userContext.authentication()),
                favoriteMenuService.findFavorites(),
                Map.copyOf(codes),
                new VersionView(
                        catalog.authorityVersion(),
                        catalog.menuVersion(),
                        catalog.programVersion(),
                        commonCodeService.groupVersions()));
    }

    /** 화면 초기화에 필요한 모든 업무 데이터를 담는 서버 측 스냅샷이다. */
    public record BootstrapView(
            UserView user,
            List<MenuAuthorizationService.MenuNode> menus,
            List<ProgramAuthorizationService.ActionPermission> programActions,
            List<FavoriteMenuService.FavoriteMenu> favorites,
            Map<String, CommonCodeService.CodeGroupView> codeGroups,
            VersionView versions) {
    }

    /** 현재 사용자 식별 정보와 계산 완료된 유효 권한 목록이다. */
    public record UserView(
            String username,
            String organizationId,
            List<String> effectiveAuthorityIds) {
    }

    /** 클라이언트 캐시 갱신 판단에 사용하는 권한·메뉴·프로그램·공통코드 버전이다. */
    public record VersionView(
            long authorityVersion,
            long menuVersion,
            long programVersion,
            Map<String, Long> commonCodeVersions) {
    }
}
