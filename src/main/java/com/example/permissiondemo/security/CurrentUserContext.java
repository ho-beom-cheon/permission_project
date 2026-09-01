package com.example.permissiondemo.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;

/**
 * 서비스가 Spring Security 정적 컨텍스트나 HTTP 요청 객체에 직접 의존하지 않고
 * 현재 사용자와 요청 추적 정보를 사용할 수 있게 하는 공통 계약이다.
 */
public interface CurrentUserContext {

    /** 인증 사용자가 있으면 요청 컨텍스트를 반환하고 없으면 빈 Optional을 반환한다. */
    Optional<CurrentUser> find();

    /** 인증 사용자 컨텍스트를 반환하며 없으면 표준 미인증 예외를 발생시킨다. */
    CurrentUser require();

    /** 메뉴·기능 권한 서비스에 전달할 원본 Spring Security 인증 객체를 반환한다. */
    Authentication authentication();

    /** 서버가 신뢰하는 사용자명·현재 조직과 요청 IP·traceId를 묶은 불변 값이다. */
    record CurrentUser(
            String username,
            String organizationId,
            String clientIp,
            String traceId) {
    }
}
