package com.example.permissiondemo.common;

import java.util.List;

/** 공통코드 서비스의 demo/Oracle 저장 전략을 분리하는 계약이다. */
public interface CommonCodePersistence {

    boolean persistent();

    List<StoredGroup> load();

    void saveGroup(CommonCodeService.CommonCodeGroup group, long version);

    void deleteGroup(String groupCode);

    void saveItems(
            CommonCodeService.CommonCodeGroup group,
            List<CommonCodeService.CommonCodeItem> items,
            long version);

    void deleteItem(String groupCode, String code, long version);

    record StoredGroup(
            CommonCodeService.CommonCodeGroup group,
            long version,
            List<CommonCodeService.CommonCodeItem> items) {

        public StoredGroup {
            items = List.copyOf(items);
        }
    }
}
