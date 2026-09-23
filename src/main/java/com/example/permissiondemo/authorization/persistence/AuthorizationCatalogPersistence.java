package com.example.permissiondemo.authorization.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.authorization.AuthorizationCatalog;

/**
 * 권한 카탈로그의 Oracle 로드·변경 계약이다.
 * demo 프로필에서는 구현 빈을 만들지 않아 기존 인메모리 카탈로그가 그대로 동작한다.
 */
public interface AuthorizationCatalogPersistence {

    CatalogSnapshot loadSnapshot();

    void saveAuthority(AuthorizationCatalog.AuthorityDefinition authority);

    void deleteAuthority(String authorityId, boolean menuMappingsPresent, boolean actionMappingsPresent);

    void insertAssignment(AuthorizationCatalog.AuthorityAssignment assignment);

    void saveMenu(AuthorizationCatalog.MenuDefinition menu);

    void deleteMenu(String menuId, boolean programActionsPresent);

    void setMenuGrant(String authorityId, String menuId, boolean granted);

    void saveProgram(AuthorizationCatalog.ProgramDefinition program);

    void deleteProgram(String programId);

    void saveAction(AuthorizationCatalog.ProgramActionDefinition action);

    void deleteAction(AuthorizationCatalog.ProgramActionKey actionKey);

    void setActionGrant(
            String authorityId,
            AuthorizationCatalog.ProgramActionKey actionKey,
            boolean granted);

    /** Oracle 기동 시 한 번에 읽는 권한·메뉴·프로그램 원장 스냅샷이다. */
    record CatalogSnapshot(
            List<AuthorizationCatalog.OrganizationDefinition> organizations,
            List<AuthorizationCatalog.UserProfile> users,
            List<AuthorizationCatalog.AuthorityDefinition> authorities,
            List<AuthorizationCatalog.AuthorityAssignment> assignments,
            List<AuthorizationCatalog.MenuDefinition> menus,
            Map<String, Set<String>> menuGrants,
            List<AuthorizationCatalog.ProgramDefinition> programs,
            List<AuthorizationCatalog.ProgramActionDefinition> actions,
            Map<String, Set<AuthorizationCatalog.ProgramActionKey>> actionGrants,
            long authorityVersion,
            long menuVersion,
            long programVersion) {
    }
}
