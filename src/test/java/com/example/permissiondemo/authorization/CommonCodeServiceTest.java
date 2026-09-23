package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.permissiondemo.common.CommonCodeService;

import java.time.LocalDate;
import java.util.List;

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

    /** 프로젝트·권한·프로그램 운영에 필요한 코드 그룹과 상세 데이터가 충분히 제공되는지 확인한다. */
    @Test
    void operationalCodeGroupsAreSeeded() {
        CommonCodeService service = new CommonCodeService();

        assertThat(service.groups())
                .extracting(CommonCodeService.CommonCodeGroup::groupCode)
                .contains("AUTH_STATUS", "AUTH_TYPE", "PROJECT_STATUS", "PROJECT_ROLE",
                        "PROGRAM_TYPE", "REGION");
        assertThat(service.findActiveItems("PROJECT_STATUS"))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("PLANNED", "ACTIVE", "ON_HOLD", "COMPLETED", "ARCHIVED");
        assertThat(service.findActiveItems("AUTH_STATUS"))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .contains("PENDING", "APPROVED", "REJECTED", "CANCELLED", "REVOKED", "EXPIRED");
    }

    /** 사용자 정의 그룹을 만들고 비활성 말단 항목부터 안전하게 삭제한 뒤 그룹까지 제거한다. */
    @Test
    void customGroupAndItemLifecycleIsSafe() {
        CommonCodeService service = new CommonCodeService();
        service.saveGroup("TASK_PRIORITY", "업무 우선순위", "프로젝트 업무 우선순위", 90, true);
        service.saveItem("TASK_PRIORITY", "HIGH", "높음", 10, true);

        assertThatThrownBy(() -> service.deleteItem("TASK_PRIORITY", "HIGH"))
                .isInstanceOf(com.example.permissiondemo.web.ApiException.class);

        service.deactivateItem("TASK_PRIORITY", "HIGH");
        service.deleteItem("TASK_PRIORITY", "HIGH");
        service.saveGroup("TASK_PRIORITY", "업무 우선순위", "프로젝트 업무 우선순위", 90, false);
        service.deleteGroup("TASK_PRIORITY");

        assertThat(service.groupSummary()).doesNotContainKey("TASK_PRIORITY");
        assertThat(service.groupVersions()).doesNotContainKey("TASK_PRIORITY");
    }

    /** 상위 코드를 비활성화하면 활성 하위 코드가 남지 않고 한 번의 그룹 버전 증가로 반영된다. */
    @Test
    void parentDeactivationCascadesToDescendants() {
        CommonCodeService service = new CommonCodeService();
        long before = service.findGroupView("REGION", false, null).version();

        service.deactivateItem("REGION", "SEOUL");

        assertThat(service.findItems("REGION", false, "SEOUL"))
                .allMatch(item -> !item.active());
        assertThat(service.findGroupView("REGION", false, null).version()).isEqualTo(before + 1);
    }

    /** 동일 값을 다시 저장하는 멱등 요청은 버전이나 캐시 세대를 불필요하게 증가시키지 않는다. */
    @Test
    void unchangedSaveDoesNotAdvanceVersion() {
        CommonCodeService service = new CommonCodeService();
        CommonCodeService.CommonCodeItem current = service.findItems("USE_YN", false, null).get(0);
        long before = service.findGroupView("USE_YN", false, null).version();

        service.saveItem(
                "USE_YN", current.code(), current.name(), current.parentCode(), current.sortOrder(),
                current.active(), current.validFrom(), current.validTo());

        assertThat(service.findGroupView("USE_YN", false, null).version()).isEqualTo(before);
    }

    /** 그룹과 항목 검색이 코드값뿐 아니라 한글 표시명과 활성 상태도 함께 반영한다. */
    @Test
    void groupsAndItemsCanBeSearched() {
        CommonCodeService service = new CommonCodeService();

        assertThat(service.searchGroups("프로젝트", true))
                .extracting(CommonCodeService.CommonCodeGroup::groupCode)
                .contains("PROJECT_STATUS", "PROJECT_ROLE");
        assertThat(service.searchItems("PROJECT_ROLE", "관리자", true, null))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("MANAGER");
    }

    /** 하위 코드가 상위 코드보다 먼저 오더라도 전체 후보를 기준으로 계층을 검증한다. */
    @Test
    void bulkSaveAcceptsChildBeforeParent() {
        CommonCodeService service = new CommonCodeService();
        service.saveGroup("BATCH_TREE", "일괄 계층", "입력 순서 독립성 검증", 90, true);
        long beforeVersion = service.findGroupView("BATCH_TREE", false, null).version();

        CommonCodeService.BulkSaveResult result = service.saveItemsBulk(
                "BATCH_TREE",
                List.of(
                        item("CHILD", "하위", "PARENT", 20),
                        item("PARENT", "상위", null, 10)));

        assertThat(result.version()).isEqualTo(beforeVersion + 1);
        assertThat(result.changedCount()).isEqualTo(2);
        assertThat(service.findItems("BATCH_TREE", false, "PARENT"))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("CHILD");
        assertThat(service.findItems("BATCH_TREE", false, null))
                .extracting(CommonCodeService.CommonCodeItem::code)
                .containsExactly("PARENT", "CHILD");
    }

    /** 배치 뒤쪽 항목의 기간 검증이 실패해도 앞쪽 변경과 버전이 일부 반영되지 않는다. */
    @Test
    void invalidBulkSaveIsAtomic() {
        CommonCodeService service = new CommonCodeService();
        List<CommonCodeService.CommonCodeItem> beforeItems =
                service.findItems("REGION", false, null);
        long beforeVersion = service.findGroupView("REGION", false, null).version();

        assertThatThrownBy(() -> service.saveItemsBulk(
                "REGION",
                List.of(
                        item("JONGNO", "종로구", "SEOUL", 5),
                        new CommonCodeService.CommonCodeItem(
                                "INVALID_PERIOD", "잘못된 기간", "SEOUL", 6, true,
                                LocalDate.of(2027, 1, 1),
                                LocalDate.of(2026, 1, 1)))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.findItems("REGION", false, null)).isEqualTo(beforeItems);
        assertThat(service.findGroupView("REGION", false, null).version())
                .isEqualTo(beforeVersion);
    }

    /** 성공한 배치를 그대로 재요청하면 실제 변경 건수와 버전이 늘지 않는다. */
    @Test
    void identicalBulkRetryDoesNotAdvanceVersion() {
        CommonCodeService service = new CommonCodeService();
        service.saveGroup("BATCH_RETRY", "재요청 검증", "일괄 저장 멱등성 검증", 91, true);
        List<CommonCodeService.CommonCodeItem> request = List.of(
                item("HIGH", "높음", null, 10),
                item("LOW", "낮음", null, 20));

        CommonCodeService.BulkSaveResult first =
                service.saveItemsBulk("BATCH_RETRY", request);
        CommonCodeService.BulkSaveResult retry =
                service.saveItemsBulk("BATCH_RETRY", request);

        assertThat(first.changedCount()).isEqualTo(2);
        assertThat(retry.changedCount()).isZero();
        assertThat(retry.version()).isEqualTo(first.version());
        assertThat(service.findGroupView("BATCH_RETRY", false, null).version())
                .isEqualTo(first.version());
    }

    /** 한 배치 내 중복 코드와 상호 부모 순환을 저장 전에 모두 차단한다. */
    @Test
    void duplicateAndCyclicBulkItemsAreRejected() {
        CommonCodeService service = new CommonCodeService();
        service.saveGroup("BATCH_INVALID", "잘못된 배치", "중복과 순환 검증", 92, true);
        long beforeVersion = service.findGroupView("BATCH_INVALID", false, null).version();

        assertThatThrownBy(() -> service.saveItemsBulk(
                "BATCH_INVALID",
                List.of(
                        item("DUPLICATE", "첫 번째", null, 10),
                        item("DUPLICATE", "두 번째", null, 20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
        assertThatThrownBy(() -> service.saveItemsBulk(
                "BATCH_INVALID",
                List.of(
                        item("NODE_A", "노드 A", "NODE_B", 10),
                        item("NODE_B", "노드 B", "NODE_A", 20))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("순환");

        assertThat(service.findItems("BATCH_INVALID", false, null)).isEmpty();
        assertThat(service.findGroupView("BATCH_INVALID", false, null).version())
                .isEqualTo(beforeVersion);
    }

    private static CommonCodeService.CommonCodeItem item(
            String code,
            String name,
            String parentCode,
            int sortOrder) {
        return new CommonCodeService.CommonCodeItem(
                code, name, parentCode, sortOrder, true, null, null);
    }
}
