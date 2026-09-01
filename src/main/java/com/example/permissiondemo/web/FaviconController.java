package com.example.permissiondemo.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 브라우저의 자동 favicon 요청을 빈 204 응답으로 처리해 불필요한 404 콘솔 오류를 막는다. */
@Controller
public class FaviconController {

    /** 별도 이미지 자산 없이 성공적으로 요청을 종료한다. */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
