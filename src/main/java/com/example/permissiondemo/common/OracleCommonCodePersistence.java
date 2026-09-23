package com.example.permissiondemo.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** oracle 프로필에서 공통코드 원본과 버전을 PM_CODE_GROUP/PM_COMMON_CODE에 영속화한다. */
@Repository
@Profile("oracle")
public class OracleCommonCodePersistence implements CommonCodePersistence {

    private final CommonCodeMapper mapper;

    public OracleCommonCodePersistence(CommonCodeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public List<StoredGroup> load() {
        Map<String, List<CommonCodeService.CommonCodeItem>> itemsByGroup = mapper.selectItems()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        CommonCodeMapper.ItemRow::groupCode,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(this::toItem,
                                java.util.stream.Collectors.toList())));
        return mapper.selectGroups().stream()
                .map(row -> new StoredGroup(
                        toGroup(row), row.version(),
                        itemsByGroup.getOrDefault(row.groupCode(), List.of())))
                .toList();
    }

    @Override
    @Transactional
    public void saveGroup(CommonCodeService.CommonCodeGroup group, long version) {
        mapper.mergeGroup(toRow(group, version));
    }

    @Override
    @Transactional
    public void deleteGroup(String groupCode) {
        mapper.deleteGroup(groupCode);
    }

    @Override
    @Transactional
    public void saveItems(
            CommonCodeService.CommonCodeGroup group,
            List<CommonCodeService.CommonCodeItem> items,
            long version) {
        mapper.mergeGroup(toRow(group, version));
        for (CommonCodeService.CommonCodeItem item : items) {
            mapper.mergeItem(toRow(group.groupCode(), item));
        }
        mapper.updateGroupVersion(group.groupCode(), version);
    }

    @Override
    @Transactional
    public void deleteItem(String groupCode, String code, long version) {
        mapper.deleteItem(groupCode, code);
        mapper.updateGroupVersion(groupCode, version);
    }

    private CommonCodeService.CommonCodeGroup toGroup(CommonCodeMapper.GroupRow row) {
        return new CommonCodeService.CommonCodeGroup(
                row.groupCode(), row.groupName(), row.description(), row.sortOrder(),
                row.active(), row.system());
    }

    private CommonCodeService.CommonCodeItem toItem(CommonCodeMapper.ItemRow row) {
        return new CommonCodeService.CommonCodeItem(
                row.code(), row.codeName(), row.parentCode(), row.sortOrder(), row.active(),
                row.validFrom(), row.validTo());
    }

    private CommonCodeMapper.GroupRow toRow(
            CommonCodeService.CommonCodeGroup group, long version) {
        return new CommonCodeMapper.GroupRow(
                group.groupCode(), group.name(), group.description(), group.sortOrder(),
                group.active(), group.system(), version);
    }

    private CommonCodeMapper.ItemRow toRow(
            String groupCode, CommonCodeService.CommonCodeItem item) {
        return new CommonCodeMapper.ItemRow(
                groupCode, item.code(), item.name(), item.parentCode(), item.sortOrder(),
                item.active(), item.validFrom(), item.validTo());
    }
}
