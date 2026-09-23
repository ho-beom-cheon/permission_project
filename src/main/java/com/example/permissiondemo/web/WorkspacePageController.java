package com.example.permissiondemo.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 기본 진입은 사용자 포털로, 관리 콘솔은 명시적인 /admin 경로로 연결한다. */
@Controller
public class WorkspacePageController {
    @GetMapping({"/", "/portal", "/portal/"})
    public String portal() {
        return "forward:/portal.html";
    }

    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "forward:/index.html";
    }
}
