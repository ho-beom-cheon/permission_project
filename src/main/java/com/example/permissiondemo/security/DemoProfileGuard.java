package com.example.permissiondemo.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 소스에 포함된 알려진 데모 계정이 운영·혼합 프로필에서 실수로 실행되는 것을 막는다.
 * 활성 프로필이 정확히 {@code demo} 하나가 아니면 애플리케이션 기동을 즉시 중단한다.
 */
@Configuration
public class DemoProfileGuard {

    private final Environment environment;

    public DemoProfileGuard(Environment environment) {
        this.environment = environment;
    }

    /** 활성 프로필이 없으면 기본 프로필까지 확인해 우회 기동을 차단한다. */
    @PostConstruct
    void validateProfiles() {
        String[] activeProfiles = environment.getActiveProfiles();
        String[] effectiveProfiles = activeProfiles.length == 0
                ? environment.getDefaultProfiles()
                : activeProfiles;
        Set<String> profileSet = Arrays.stream(effectiveProfiles)
                .collect(Collectors.toUnmodifiableSet());

        // demo와 다른 프로필을 함께 지정한 경우도 허용하지 않는다.
        if (!profileSet.equals(Set.of("demo"))) {
            throw new IllegalStateException(
                    "이 프로젝트는 demo 프로필 하나로만 실행할 수 있습니다. 현재 프로필: "
                            + profileSet);
        }
    }
}
