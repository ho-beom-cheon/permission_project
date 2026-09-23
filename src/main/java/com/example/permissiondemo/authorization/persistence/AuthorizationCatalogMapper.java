package com.example.permissiondemo.authorization.persistence;

import java.util.List;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** PM_* 권한·메뉴·프로그램 원장 SQL을 XML로 분리하는 MyBatis 계약이다. */
@Mapper
public interface AuthorizationCatalogMapper {

    List<AuthorizationCatalog.OrganizationDefinition> selectOrganizations();

    List<AuthorizationCatalog.UserProfile> selectEnabledUsers();

    List<AuthorizationCatalog.AuthorityDefinition> selectAuthorities();

    List<AuthorizationCatalog.AuthorityAssignment> selectAssignments();

    List<AuthorizationCatalog.MenuDefinition> selectMenus();

    List<MenuGrantRow> selectMenuGrants();

    List<AuthorizationCatalog.ProgramDefinition> selectPrograms();

    List<AuthorizationCatalog.ProgramActionDefinition> selectActions();

    List<ActionGrantRow> selectActionGrants();

    List<DomainVersionRow> selectDomainVersions();

    int mergeAuthority(@Param("authority") AuthorizationCatalog.AuthorityDefinition authority);

    int deleteAuthorityMenuGrants(@Param("authorityId") String authorityId);

    int deleteAuthorityActionGrants(@Param("authorityId") String authorityId);

    int deleteAuthority(@Param("authorityId") String authorityId);

    int insertAssignment(@Param("assignment") AuthorizationCatalog.AuthorityAssignment assignment);

    int mergeMenu(@Param("menu") AuthorizationCatalog.MenuDefinition menu);

    int deleteFavoriteMenusByMenu(@Param("menuId") String menuId);

    int deleteMenuGrantsByMenu(@Param("menuId") String menuId);

    int deleteActionGrantsByMenu(@Param("menuId") String menuId);

    int deleteActionsByMenu(@Param("menuId") String menuId);

    int deleteMenu(@Param("menuId") String menuId);

    int mergeMenuGrant(@Param("authorityId") String authorityId, @Param("menuId") String menuId);

    int deleteMenuGrant(@Param("authorityId") String authorityId, @Param("menuId") String menuId);

    int mergeProgram(@Param("program") AuthorizationCatalog.ProgramDefinition program);

    int deleteActionGrantsByProgram(@Param("programId") String programId);

    int deleteActionsByProgram(@Param("programId") String programId);

    int deleteProgram(@Param("programId") String programId);

    int mergeAction(@Param("action") AuthorizationCatalog.ProgramActionDefinition action);

    int deleteActionGrantsByAction(@Param("key") AuthorizationCatalog.ProgramActionKey key);

    int deleteAction(@Param("key") AuthorizationCatalog.ProgramActionKey key);

    int mergeActionGrant(
            @Param("authorityId") String authorityId,
            @Param("key") AuthorizationCatalog.ProgramActionKey key);

    int deleteActionGrant(
            @Param("authorityId") String authorityId,
            @Param("key") AuthorizationCatalog.ProgramActionKey key);

    int bumpDomainVersion(@Param("domainCode") String domainCode);

    List<String> selectFavoriteMenuIds(@Param("username") String username);

    int insertFavorite(@Param("username") String username, @Param("menuId") String menuId);

    int deleteFavorite(@Param("username") String username, @Param("menuId") String menuId);

    record MenuGrantRow(String authorityId, String menuId) {
    }

    record ActionGrantRow(String authorityId, String menuId, String programId, String actionId) {

        public AuthorizationCatalog.ProgramActionKey key() {
            return new AuthorizationCatalog.ProgramActionKey(menuId, programId, actionId);
        }
    }

    record DomainVersionRow(String domainCode, long version) {
    }
}
