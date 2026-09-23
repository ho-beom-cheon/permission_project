package com.example.permissiondemo.security;

import java.util.Optional;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Oracle 로컬 로그인 계정을 조회하는 MyBatis 매퍼다. */
@Mapper
public interface LocalAccountMapper {

    Optional<LocalAccountRow> selectByUsername(@Param("username") String username);

    record LocalAccountRow(String username, String passwordHash, boolean enabled) {
    }
}
