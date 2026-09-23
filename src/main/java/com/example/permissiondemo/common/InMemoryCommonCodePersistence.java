package com.example.permissiondemo.common;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** demo 프로필에서는 서비스 자체의 동시성 안전 인메모리 상태를 사용한다. */
@Component
@Profile("demo")
public class InMemoryCommonCodePersistence implements CommonCodePersistence {

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public List<StoredGroup> load() {
        return List.of();
    }

    @Override
    public void saveGroup(CommonCodeService.CommonCodeGroup group, long version) {
    }

    @Override
    public void deleteGroup(String groupCode) {
    }

    @Override
    public void saveItems(
            CommonCodeService.CommonCodeGroup group,
            List<CommonCodeService.CommonCodeItem> items,
            long version) {
    }

    @Override
    public void deleteItem(String groupCode, String code, long version) {
    }
}
