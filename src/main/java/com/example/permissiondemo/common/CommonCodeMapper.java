package com.example.permissiondemo.common;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Oracle 공통코드 그룹·항목·버전을 저장하는 MyBatis 매퍼다. */
@Mapper
public interface CommonCodeMapper {

    List<GroupRow> selectGroups();

    List<ItemRow> selectItems();

    int mergeGroup(@Param("row") GroupRow row);

    int updateGroupVersion(
            @Param("groupCode") String groupCode,
            @Param("version") long version);

    int mergeItem(@Param("row") ItemRow row);

    int deleteItem(@Param("groupCode") String groupCode, @Param("code") String code);

    int deleteGroup(@Param("groupCode") String groupCode);

    record GroupRow(
            String groupCode,
            String groupName,
            String description,
            int sortOrder,
            boolean active,
            boolean system,
            long version) {

        public String activeYn() {
            return active ? "Y" : "N";
        }

        public String systemYn() {
            return system ? "Y" : "N";
        }
    }

    record ItemRow(
            String groupCode,
            String code,
            String codeName,
            String parentCode,
            int sortOrder,
            boolean active,
            LocalDate validFrom,
            LocalDate validTo) {

        public String activeYn() {
            return active ? "Y" : "N";
        }
    }
}
