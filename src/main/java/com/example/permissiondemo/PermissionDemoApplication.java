package com.example.permissiondemo;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 권한·메뉴·공통코드 데모 애플리케이션의 실행 진입점이다.
 * 업무 시간 판정은 서버 기본 시간대에 의존하지 않도록 Asia/Seoul 기준 Clock을 주입한다.
 */
@SpringBootApplication
public class PermissionDemoApplication {

    /** Spring Boot 애플리케이션 컨텍스트와 내장 웹 서버를 시작한다. */
    public static void main(String[] args) {
        SpringApplication.run(PermissionDemoApplication.class, args);
    }

    /** 권한 유효기간과 공통코드 적용기간 계산에 공통으로 사용하는 서울 시간대 시계다. */
    @Bean
    Clock systemClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
