package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.permissiondemo.common.CommonCodeService;

import org.junit.jupiter.api.Test;

/**
 * 공통코드의 활성 필터, 정렬, 입력 정규화, 계층 무결성, 버전 증가 규칙을 검증한다.
 * 저장 구현을 DB 저장소로 교체하더라도 이 테스트의 외부 계약은 그대로 유지해야 한다.
 */
class CommonCodeServiceTest {

    /** 비활성 코드는 조회 결과에서 빠지고 sortOrder 기준 순서가 보장되는지 확인한다. */
    @Test
    void onlyActiveCodesAreReturnedInSortOrder() {
        CommonCodeService service = new CommonCodeService();

        assertThat(service.findActiveItems("ARTICLE_STATUS"))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("DRAFT", "PUBLISHED");
    }

    /** 코드 대문자·공백 정규화와 허용하지 않는 코드 형식의 거부를 함께 확인한다. */
    @Test
    void saveNormalizesCodeAndValidatesInput() {
        CommonCodeService service = new CommonCodeService();

        CommonCodeService.CommonCodeItem saved = service.saveItem(
                "USE_YN", " demo ", "테스트", 15, true);

        assertThat(saved.code()).isEqualTo("DEMO");
        assertThat(service.findActiveItems("USE_YN"))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("Y", "DEMO", "N");
        assertThatThrownBy(() -> service.saveItem("USE_YN", "../bad", "오류", 10, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 상하위 코드 조회와 저장 직후 캐시 버전 증가가 하나의 변경 단위로 보이는지 확인한다. */
    @Test
    void hierarchyAndVersionAreUpdatedTogether() {
        CommonCodeService service = new CommonCodeService();
        long previousVersion = service.findGroupView("REGION", true, "SEOUL").version();

        assertThat(service.findItems("REGION", true, "SEOUL"))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("GANGNAM", "MAPO");

        service.saveItem(
                "REGION", "JONGNO", "종로구", "SEOUL", 5, true, null, null);

        CommonCodeService.CodeGroupView updated =
                service.findGroupView("REGION", true, "SEOUL");
        assertThat(updated.version()).isEqualTo(previousVersion + 1);
        assertThat(updated.items())
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("JONGNO", "GANGNAM", "MAPO");
    }

    /** 존재하지 않는 부모 참조와 역전된 적용 기간이 저장 전에 거부되는지 확인한다. */
    @Test
    void invalidParentAndPeriodAreRejected() {
        CommonCodeService service = new CommonCodeService();

        assertThatThrownBy(() -> service.saveItem(
                "REGION", "CHILD", "잘못된 하위 코드", "UNKNOWN", 10, true, null, null))
                .isInstanceOf(com.example.permissiondemo.web.ApiException.class);
        assertThatThrownBy(() -> service.saveItem(
                "REGION", "CHILD", "잘못된 기간", "SEOUL", 10, true,
                java.time.LocalDate.of(2027, 1, 1),
                java.time.LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
