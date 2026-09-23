package com.example.permissiondemo.authorization.persistence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** demo 프로필에서 사용자별 관심 메뉴를 인메모리 집합으로 보관한다. */
@Repository
@Profile("demo")
public class InMemoryFavoriteMenuRepository implements FavoriteMenuRepository {

    private final ConcurrentMap<String, Set<String>> favorites = new ConcurrentHashMap<>();

    @Override
    public Set<String> findMenuIds(String username) {
        return Set.copyOf(favorites.getOrDefault(username, Set.of()));
    }

    @Override
    public void add(String username, String menuId) {
        favorites.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(menuId);
    }

    @Override
    public void remove(String username, String menuId) {
        Set<String> stored = favorites.get(username);
        if (stored != null) {
            stored.remove(menuId);
        }
    }
}
