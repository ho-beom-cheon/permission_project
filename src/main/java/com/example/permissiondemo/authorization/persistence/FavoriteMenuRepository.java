package com.example.permissiondemo.authorization.persistence;

import java.util.Set;

/** 사용자 관심 메뉴의 demo·Oracle 공통 저장 계약이다. */
public interface FavoriteMenuRepository {

    Set<String> findMenuIds(String username);

    void add(String username, String menuId);

    void remove(String username, String menuId);
}
