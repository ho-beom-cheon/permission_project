package com.example.permissiondemo.web;

import java.time.LocalDate;
import java.util.List;

import com.example.permissiondemo.authorization.EffectiveAuthorityService;
import com.example.permissiondemo.authorization.MenuAuthorizationService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.security.CurrentUserContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 사용자, 메뉴, 프로그램 기능 권한과 기본 공통코드 API를 제공한다.
 * 조회 결과는 요청이 보낸 권한 값이 아니라 서버의 인증 세션과 권한 카탈로그만 사용해 계산한다.
 */
@RestController
@RequestMapping("/api")
public class PermissionApiController {

    private final EffectiveAuthorityService effectiveAuthorityService;
    private final MenuAuthorizationService menuAuthorizationService;
    private final ProgramAuthorizationService programAuthorizationService;
    private final CommonCodeService commonCodeService;
    private final CurrentUserContext userContext;

    public PermissionApiController(
            EffectiveAuthorityService effectiveAuthorityService,
            MenuAuthorizationService menuAuthorizationService,
            ProgramAuthorizationService programAuthorizationService,
            CommonCodeService commonCodeService,
            CurrentUserContext userContext) {
        this.effectiveAuthorityService = effectiveAuthorityService;
        this.menuAuthorizationService = menuAuthorizationService;
        this.programAuthorizationService = programAuthorizationService;
        this.commonCodeService = commonCodeService;
        this.userContext = userContext;
    }

    /** 인증 사용자와 현재 조직, 계산된 유효 업무 권한을 반환한다. */
    @GetMapping("/me")
    public ApiResponse<CurrentUserView> currentUser() {
        CurrentUserContext.CurrentUser current = userContext.require();
        List<String> authorityIds = effectiveAuthorityService
                .findEffectiveAuthorityIds(userContext.authentication()).stream()
                .sorted()
                .toList();
        return ApiResponse.ok(new CurrentUserView(
                current.username(), current.organizationId(), authorityIds));
    }

    /** 공용 메뉴와 현재 유효 권한 메뉴를 합친 화면용 계층 트리를 반환한다. */
    @GetMapping("/menus")
    public ApiResponse<List<MenuAuthorizationService.MenuNode>> menus(Authentication authentication) {
        return ApiResponse.ok(menuAuthorizationService.findAuthorizedMenuTree(authentication));
    }

    /** 특정 메뉴·프로그램 문맥에서 화면에 활성화할 수 있는 기능 목록을 반환한다. */
    @GetMapping("/menus/{menuId}/programs/{programId}/actions")
    public ApiResponse<List<ProgramAuthorizationService.ActionPermission>> programActions(
            Authentication authentication,
            @PathVariable String menuId,
            @PathVariable String programId) {
        return ApiResponse.ok(
                programAuthorizationService.findAllowedActions(authentication, menuId, programId));
    }

    /** 현재 날짜에 신규 입력 선택지로 사용할 수 있는 활성 공통코드를 반환한다. */
    @GetMapping("/common-codes/{groupCode}")
    public ApiResponse<List<CommonCodeService.CommonCodeItem>> commonCodes(
            @PathVariable String groupCode) {
        return ApiResponse.ok(commonCodeService.findActiveItems(groupCode));
    }

    /**
     * 공통코드 한 건을 추가하거나 수정한다.
     * Bean Validation과 서비스 관계 검증을 모두 통과하고 COMMON_CODE_SAVE 권한이 있어야 한다.
     */
    @PostMapping("/common-codes/{groupCode}/items")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_SAVE')")
    public ApiResponse<CommonCodeService.CommonCodeItem> saveCommonCode(
            @PathVariable String groupCode,
            @Valid @RequestBody SaveCommonCodeRequest request) {
        return ApiResponse.ok(commonCodeService.saveItem(
                groupCode,
                request.code(),
                request.name(),
                request.parentCode(),
                request.sortOrder(),
                request.active(),
                request.validFrom(),
                request.validTo()));
    }

    /** 현재 세션에 연결된 CSRF 헤더명, 폼 파라미터명과 토큰을 반환한다. */
    @GetMapping("/csrf")
    public ApiResponse<CsrfView> csrf(@RequestAttribute("_csrf") CsrfToken csrfToken) {
        return ApiResponse.ok(new CsrfView(
                csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken()));
    }

    /** 현재 사용자 조회 API의 최소 응답이다. */
    public record CurrentUserView(
            String username, String organizationId, List<String> effectiveAuthorityIds) {
    }

    /**
     * 공통코드 저장 요청이다.
     * 단일 필드 형식은 Bean Validation이, 기간과 상위 코드 관계는 서비스가 추가 검증한다.
     */
    public record SaveCommonCodeRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9_]{1,30}",
                    message = "code는 영문, 숫자, 밑줄만 사용할 수 있습니다.")
            String code,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "[A-Za-z0-9_]{1,30}",
                    message = "parentCode 형식이 올바르지 않습니다.")
            String parentCode,
            @Min(0) @Max(9999) Integer sortOrder,
            Boolean active,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    /** JavaScript 요청 헤더와 HTML 폼 모두에서 사용할 수 있는 CSRF 정보다. */
    public record CsrfView(String headerName, String parameterName, String token) {
    }
}
