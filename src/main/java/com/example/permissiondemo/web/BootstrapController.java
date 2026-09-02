package com.example.permissiondemo.web;

import java.util.List;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.FavoriteMenuService;
import com.example.permissiondemo.common.BootstrapService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면 초기화, 계층 공통코드, 관심 메뉴와 감사 이력 API를 제공한다.
 * 모든 경로는 SecurityConfig에서 로그인 사용자에게만 허용된다.
 */
@RestController
@RequestMapping("/api")
@com.example.permissiondemo.storage.StateBoundary
public class BootstrapController {

    /** 로그인 직후 화면 구성에 필요한 사용자·메뉴·기능 권한·공통코드를 하나의 스냅샷으로 조합한다. */
    private final BootstrapService bootstrapService;
    /** 사용자별 관심 메뉴를 보관하며 조회와 변경 시 모두 현재 메뉴 접근 권한을 재검증한다. */
    private final FavoriteMenuService favoriteMenuService;
    /** 계층 코드의 조건 조회와 버전 기반 캐시 갱신 정보를 제공한다. */
    private final CommonCodeService commonCodeService;
    /** 보안·관리 행위를 최신 순으로 조회하는 데모용 감사 저장소다. */
    private final AuditEventService auditEventService;

    public BootstrapController(
            BootstrapService bootstrapService,
            FavoriteMenuService favoriteMenuService,
            CommonCodeService commonCodeService,
            AuditEventService auditEventService) {
        this.bootstrapService = bootstrapService;
        this.favoriteMenuService = favoriteMenuService;
        this.commonCodeService = commonCodeService;
        this.auditEventService = auditEventService;
    }

    /**
     * 사용자와 권한 관련 초기 데이터를 한 번에 반환하며 같은 응답에 현재 세션 CSRF 토큰을 포함한다.
     * codeGroup 파라미터가 없으면 화면 예제에 필요한 USE_YN과 REGION을 조회한다.
     * 초기 렌더링에 필요한 정보를 묶어 왕복 요청 수를 줄이되, 권한 변경 후에는 다시 호출해 최신 권한을 받아야 한다.
     */
    @GetMapping("/bootstrap")
    public ApiResponse<BootstrapView> bootstrap(
            @RequestParam(defaultValue = "USE_YN,REGION") List<String> codeGroup,
            @RequestAttribute("_csrf") CsrfToken csrfToken) {
        return ApiResponse.ok(new BootstrapView(
                bootstrapService.load(codeGroup),
                new PermissionApiController.CsrfView(
                        csrfToken.getHeaderName(),
                        csrfToken.getParameterName(),
                        csrfToken.getToken())));
    }

    /** 활성 여부와 상위 코드 조건을 적용한 코드 목록 및 그룹 버전을 반환한다. */
    @GetMapping("/common-codes/{groupCode}/view")
    public ApiResponse<CommonCodeService.CodeGroupView> codeGroup(
            @PathVariable String groupCode,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(required = false) String parentCode) {
        return ApiResponse.ok(commonCodeService.findGroupView(
                groupCode, activeOnly, parentCode));
    }

    /**
     * 현재 사용자에게 여전히 접근 가능한 관심 메뉴만 반환한다.
     * 과거에 등록했더라도 권한 회수·메뉴 비활성화 후에는 반환하지 않아 오래된 즐겨찾기가 접근 우회 경로가 되지 않는다.
     */
    @GetMapping("/me/favorite-menus")
    public ApiResponse<List<FavoriteMenuService.FavoriteMenu>> favorites() {
        return ApiResponse.ok(favoriteMenuService.findFavorites());
    }

    /**
     * 서버에서 메뉴 접근 권한을 재검증한 뒤 현재 사용자의 관심 메뉴를 등록한다.
     * 브라우저가 임의 menuId를 보내더라도 권한 없는 메뉴나 상위 컨테이너는 저장할 수 없다.
     */
    @PostMapping("/me/favorite-menus/{menuId}")
    public ApiResponse<FavoriteMenuService.FavoriteMenu> addFavorite(
            @PathVariable String menuId) {
        return ApiResponse.ok(favoriteMenuService.addFavorite(menuId));
    }

    /** 현재 사용자의 관심 메뉴 한 건을 삭제한다. 상태 변경 요청이므로 CSRF 검사를 받는다. */
    @DeleteMapping("/me/favorite-menus/{menuId}")
    public ApiResponse<Void> removeFavorite(@PathVariable String menuId) {
        favoriteMenuService.removeFavorite(menuId);
        return ApiResponse.ok(null);
    }

    /**
     * 감사 이벤트를 최신 순으로 페이징해 반환한다.
     * 민감한 보안 이력이므로 권한 관리 조회 기능이 허용된 사용자만 접근할 수 있다.
     */
    @GetMapping("/audit-events")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<PageResult<AuditEventService.AuditEvent>> auditEvents(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(auditEventService.findPage(PageQuery.of(page, size)));
    }

    /** 서버 초기화 스냅샷과 현재 세션 CSRF 헤더·파라미터 정보를 묶은 HTTP 응답이다. */
    public record BootstrapView(
            BootstrapService.BootstrapView context,
            PermissionApiController.CsrfView csrf) {
    }
}
