package com.example.permissiondemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.permissiondemo.audit.AuditEventService;
import org.junit.jupiter.api.Test;

class CommonCodePersistenceTest {

    @Test
    void loadsOracleSnapshotAndPersistsCascadeWithOneVersion() {
        RecordingPersistence persistence = new RecordingPersistence();
        CommonCodeService service = new CommonCodeService(
                new AuditEventService(), Clock.systemUTC(), persistence);

        assertThat(service.groups()).extracting(CommonCodeService.CommonCodeGroup::groupCode)
                .containsExactly("CUSTOM");

        service.deactivateItem("CUSTOM", "ROOT");

        assertThat(persistence.savedVersion).isEqualTo(4);
        assertThat(persistence.savedItems)
                .extracting(CommonCodeService.CommonCodeItem::code,
                        CommonCodeService.CommonCodeItem::active)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ROOT", false),
                        org.assertj.core.groups.Tuple.tuple("CHILD", false));
        assertThat(service.findItems("CUSTOM", false, null))
                .allMatch(item -> !item.active());
    }

    private static final class RecordingPersistence implements CommonCodePersistence {
        private long savedVersion;
        private List<CommonCodeService.CommonCodeItem> savedItems = List.of();

        @Override
        public boolean persistent() {
            return true;
        }

        @Override
        public List<StoredGroup> load() {
            LocalDate from = LocalDate.of(2020, 1, 1);
            LocalDate to = LocalDate.of(2099, 12, 31);
            return List.of(new StoredGroup(
                    new CommonCodeService.CommonCodeGroup(
                            "CUSTOM", "사용자 그룹", "영속화 검증", 10, true, false),
                    3,
                    List.of(
                            new CommonCodeService.CommonCodeItem(
                                    "ROOT", "상위", null, 10, true, from, to),
                            new CommonCodeService.CommonCodeItem(
                                    "CHILD", "하위", "ROOT", 20, true, from, to))));
        }

        @Override
        public void saveGroup(CommonCodeService.CommonCodeGroup group, long version) {
            savedVersion = version;
        }

        @Override
        public void deleteGroup(String groupCode) {
        }

        @Override
        public void saveItems(
                CommonCodeService.CommonCodeGroup group,
                List<CommonCodeService.CommonCodeItem> items,
                long version) {
            savedVersion = version;
            savedItems = new ArrayList<>(items);
        }

        @Override
        public void deleteItem(String groupCode, String code, long version) {
            savedVersion = version;
        }
    }
}
