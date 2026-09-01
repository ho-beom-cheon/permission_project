package com.example.permissiondemo.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 인메모리 데모 계정과 데이터가 운영 프로필에서 실수로 기동되지 않도록 하는 시작 가드를 검증한다.
 */
class DemoProfileGuardTest {

    /** 활성 프로필이 없을 때의 기본 demo와 명시적 demo 단독 실행은 허용되는지 확인한다. */
    @Test
    void defaultOrActiveDemoProfileIsAllowed() {
        MockEnvironment defaultDemo = new MockEnvironment();
        defaultDemo.setDefaultProfiles("demo");
        assertThatCode(() -> new DemoProfileGuard(defaultDemo).validateProfiles())
                .doesNotThrowAnyException();

        MockEnvironment activeDemo = new MockEnvironment();
        activeDemo.setActiveProfiles("demo");
        assertThatCode(() -> new DemoProfileGuard(activeDemo).validateProfiles())
                .doesNotThrowAnyException();
    }

    /** 운영 프로필 및 demo와 운영 프로필의 혼합 실행이 모두 즉시 중단되는지 확인한다. */
    @Test
    void productionAndMixedProfilesAreRejected() {
        for (String[] profiles : new String[][]{{"prod"}, {"demo", "prod"}}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);

            assertThatThrownBy(() -> new DemoProfileGuard(environment).validateProfiles())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("demo 프로필 하나");
        }
    }
}
