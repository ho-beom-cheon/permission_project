package com.example.permissiondemo.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 관리자 업무 화면별 URL은 유지하되 공통 셸과 보안 초기화는 하나의 화면 진입점에서 처리한다.
 * 실제 화면 선택은 index.html의 클라이언트 라우팅이 담당하고, 이 컨트롤러는 새로고침·직접 URL 진입 시에도
 * 정적 자원을 정상적으로 돌려주는 서버 측 진입점 역할만 한다.
 */
@Controller
@Profile("demo")
public class AdminPageController {

    /**
     * 허용된 관리 화면 URL을 공통 관리자 셸로 내부 전달한다.
     * redirect가 아닌 forward를 사용하므로 브라우저 주소는 업무 URL로 유지되며, SecurityConfig의 인증·인가 절차도
     * 해당 요청 경로를 기준으로 먼저 적용된다.
     */
    @GetMapping({
            "/admin/home",
            "/admin/content",
            "/admin/authority-master",
            "/admin/authority",
            "/admin/menu",
            "/admin/program",
            "/admin/common-code"
    })
    public String adminPage() {
        return "forward:/index.html";
    }
}
