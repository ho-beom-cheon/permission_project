package com.example.permissiondemo.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 데모 프로필의 사용자 로그인 진입점을 정적 로그인 화면에 연결한다.
 * POST /login은 Spring Security 필터가 처리하므로 이 컨트롤러는 GET 화면만 담당한다.
 */
@Controller
@Profile("demo")
public class LoginController {

    /** 주소가 간결한 /login 요청을 실제 로그인 HTML 파일로 서버 내부 전달한다. */
    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }
}
