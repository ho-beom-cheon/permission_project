package com.example.permissiondemo.authorization.persistence;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** oracle 프로필에서 관심 메뉴를 PM_FAVORITE_MENU에 저장한다. */
@Repository
@Profile("oracle")
public class MyBatisFavoriteMenuRepository implements FavoriteMenuRepository {

    private final AuthorizationCatalogMapper mapper;

    public MyBatisFavoriteMenuRepository(AuthorizationCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findMenuIds(String username) {
        return Set.copyOf(new LinkedHashSet<>(mapper.selectFavoriteMenuIds(username)));
    }

    @Override
    @Transactional
    public void add(String username, String menuId) {
        try {
            mapper.insertFavorite(username, menuId);
        } catch (DuplicateKeyException ignored) {
            // 관심 메뉴 추가는 멱등 동작이다.
        }
    }

    @Override
    @Transactional
    public void remove(String username, String menuId) {
        mapper.deleteFavorite(username, menuId);
    }
}
