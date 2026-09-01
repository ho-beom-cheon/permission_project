package com.example.permissiondemo.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.example.permissiondemo.authorization.AuthorizationCatalog;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면 버튼 제어만으로는 보안이 되지 않음을 보여주는 서버 측 기능 권한 검증 예제다.
 * 모든 업무 API는 동일한 menuId·programId·actionId를 {@code @PreAuthorize}에서 다시 확인한다.
 */
@RestController
@RequestMapping("/api")
public class DemoBusinessController {

    private final AuthorizationCatalog authorizationCatalog;

    public DemoBusinessController(AuthorizationCatalog authorizationCatalog) {
        this.authorizationCatalog = authorizationCatalog;
    }

    /** CONTENT_READ 기능이 있는 사용자에게만 콘텐츠 조회 성공 응답을 반환한다. */
    @GetMapping("/content/preview")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'CONTENT_LIST', 'CONTENT', 'CONTENT_READ')")
    public ApiResponse<Map<String, String>> previewContent() {
        return ApiResponse.ok(result("콘텐츠 조회 권한 검증 성공"));
    }

    /** CONTENT_SAVE 기능과 유효한 CSRF 토큰이 있는 경우에만 저장 예제를 실행한다. */
    @PostMapping("/content/save")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'CONTENT_LIST', 'CONTENT', 'CONTENT_SAVE')")
    public ApiResponse<Map<String, String>> saveContent() {
        return ApiResponse.ok(result("콘텐츠 저장 권한 검증 성공"));
    }

    /** CONTENT_PUBLISH 기능과 유효한 CSRF 토큰이 있는 경우에만 게시 예제를 실행한다. */
    @PostMapping("/content/publish")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'CONTENT_LIST', 'CONTENT', 'CONTENT_PUBLISH')")
    public ApiResponse<Map<String, String>> publishContent() {
        return ApiResponse.ok(result("콘텐츠 게시 권한 검증 성공"));
    }

    /** 권한 관리 조회 기능이 허용된 사용자에게만 권한 마스터를 반환한다. */
    @GetMapping("/admin/authorities")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, "
            + "'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<List<AuthorizationCatalog.AuthorityDefinition>> authorityDefinitions() {
        return ApiResponse.ok(authorizationCatalog.authorities());
    }

    /** 테스트 화면에서 처리 시각과 성공 메시지를 확인할 수 있는 공통 결과를 만든다. */
    private Map<String, String> result(String message) {
        return Map.of("message", message, "processedAt", Instant.now().toString());
    }
}
