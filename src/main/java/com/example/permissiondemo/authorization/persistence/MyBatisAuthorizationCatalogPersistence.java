package com.example.permissiondemo.authorization.persistence;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** oracle 프로필에서 AuthorizationCatalog의 전체 원장을 PM_* 테이블에 영속화한다. */
@Repository
@Profile("oracle")
public class MyBatisAuthorizationCatalogPersistence implements AuthorizationCatalogPersistence {

    private static final String DOMAIN_AUTHORITY = "AUTHORITY";
    private static final String DOMAIN_MENU = "MENU";
    private static final String DOMAIN_PROGRAM = "PROGRAM_ACTION";

    private final AuthorizationCatalogMapper mapper;

    public MyBatisAuthorizationCatalogPersistence(AuthorizationCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSnapshot loadSnapshot() {
        Map<String, Set<String>> menuGrants = new LinkedHashMap<>();
        mapper.selectMenuGrants().forEach(row -> menuGrants
                .computeIfAbsent(row.authorityId(), ignored -> new LinkedHashSet<>())
                .add(row.menuId()));
        Map<String, Set<AuthorizationCatalog.ProgramActionKey>> actionGrants =
                new LinkedHashMap<>();
        mapper.selectActionGrants().forEach(row -> actionGrants
                .computeIfAbsent(row.authorityId(), ignored -> new LinkedHashSet<>())
                .add(row.key()));
        Map<String, Long> versions = new LinkedHashMap<>();
        mapper.selectDomainVersions().forEach(row -> versions.put(row.domainCode(), row.version()));

        return new CatalogSnapshot(
                mapper.selectOrganizations(),
                mapper.selectEnabledUsers(),
                mapper.selectAuthorities(),
                mapper.selectAssignments(),
                mapper.selectMenus(),
                immutableSetMap(menuGrants),
                mapper.selectPrograms(),
                mapper.selectActions(),
                immutableSetMap(actionGrants),
                versions.getOrDefault(DOMAIN_AUTHORITY, 1L),
                versions.getOrDefault(DOMAIN_MENU, 1L),
                versions.getOrDefault(DOMAIN_PROGRAM, 1L));
    }

    @Override
    @Transactional
    public void saveAuthority(AuthorizationCatalog.AuthorityDefinition authority) {
        mapper.mergeAuthority(authority);
        mapper.bumpDomainVersion(DOMAIN_AUTHORITY);
    }

    @Override
    @Transactional
    public void deleteAuthority(
            String authorityId, boolean menuMappingsPresent, boolean actionMappingsPresent) {
        try {
            mapper.deleteAuthorityMenuGrants(authorityId);
            mapper.deleteAuthorityActionGrants(authorityId);
            requireDeleted(mapper.deleteAuthority(authorityId), authorityId);
            mapper.bumpDomainVersion(DOMAIN_AUTHORITY);
            if (menuMappingsPresent) {
                mapper.bumpDomainVersion(DOMAIN_MENU);
            }
            if (actionMappingsPresent) {
                mapper.bumpDomainVersion(DOMAIN_PROGRAM);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.CONFLICT, authorityId);
        }
    }

    @Override
    @Transactional
    public void insertAssignment(AuthorizationCatalog.AuthorityAssignment assignment) {
        try {
            mapper.insertAssignment(assignment);
            mapper.bumpDomainVersion(DOMAIN_AUTHORITY);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.CONFLICT, assignment.authorityId());
        }
    }

    @Override
    @Transactional
    public void saveMenu(AuthorizationCatalog.MenuDefinition menu) {
        mapper.mergeMenu(menu);
        mapper.bumpDomainVersion(DOMAIN_MENU);
    }

    @Override
    @Transactional
    public void deleteMenu(String menuId, boolean programActionsPresent) {
        try {
            mapper.deleteFavoriteMenusByMenu(menuId);
            mapper.deleteMenuGrantsByMenu(menuId);
            mapper.deleteActionGrantsByMenu(menuId);
            mapper.deleteActionsByMenu(menuId);
            requireDeleted(mapper.deleteMenu(menuId), menuId);
            mapper.bumpDomainVersion(DOMAIN_MENU);
            if (programActionsPresent) {
                mapper.bumpDomainVersion(DOMAIN_PROGRAM);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.CONFLICT, menuId);
        }
    }

    @Override
    @Transactional
    public void setMenuGrant(String authorityId, String menuId, boolean granted) {
        if (granted) {
            mapper.mergeMenuGrant(authorityId, menuId);
        } else {
            mapper.deleteMenuGrant(authorityId, menuId);
        }
        mapper.bumpDomainVersion(DOMAIN_MENU);
    }

    @Override
    @Transactional
    public void saveProgram(AuthorizationCatalog.ProgramDefinition program) {
        mapper.mergeProgram(program);
        mapper.bumpDomainVersion(DOMAIN_PROGRAM);
    }

    @Override
    @Transactional
    public void deleteProgram(String programId) {
        try {
            mapper.deleteActionGrantsByProgram(programId);
            mapper.deleteActionsByProgram(programId);
            requireDeleted(mapper.deleteProgram(programId), programId);
            mapper.bumpDomainVersion(DOMAIN_PROGRAM);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.CONFLICT, programId);
        }
    }

    @Override
    @Transactional
    public void saveAction(AuthorizationCatalog.ProgramActionDefinition action) {
        mapper.mergeAction(action);
        mapper.bumpDomainVersion(DOMAIN_PROGRAM);
    }

    @Override
    @Transactional
    public void deleteAction(AuthorizationCatalog.ProgramActionKey actionKey) {
        mapper.deleteActionGrantsByAction(actionKey);
        requireDeleted(mapper.deleteAction(actionKey), actionKey.actionId());
        mapper.bumpDomainVersion(DOMAIN_PROGRAM);
    }

    @Override
    @Transactional
    public void setActionGrant(
            String authorityId,
            AuthorizationCatalog.ProgramActionKey actionKey,
            boolean granted) {
        if (granted) {
            mapper.mergeActionGrant(authorityId, actionKey);
        } else {
            mapper.deleteActionGrant(authorityId, actionKey);
        }
        mapper.bumpDomainVersion(DOMAIN_PROGRAM);
    }

    private void requireDeleted(int affectedRows, String targetId) {
        if (affectedRows == 0) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, targetId);
        }
    }

    private <K, V> Map<K, Set<V>> immutableSetMap(Map<K, Set<V>> source) {
        Map<K, Set<V>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return Map.copyOf(copy);
    }
}
