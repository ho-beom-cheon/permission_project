package com.example.permissiondemo.authorization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.stereotype.Service;

/** 원본 신청 화면의 AI 권한 자동 추가 규칙을 UI가 조회할 수 있는 읽기 전용 계약이다. */
@Service
public class LinkedAuthorityRuleService {

    private static final String CY_OFFICE_CUSTOMER_AUTHORITY = "CY0201004004";
    private static final Set<String> CY_TARGET_DEPARTMENTS = Set.of("610", "630");
    private static final Map<String, List<String>> AI_TO_AB = linkedRules();
    private final AuthorizationCatalog catalog;

    public LinkedAuthorityRuleService(AuthorizationCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * GRANT 화면에서 자동 추가할 권한과 최종 선택 집합을 계산한다.
     * 이 결과는 신청을 강제 변경하지 않으며 CHANGE와 AB 직접 선택에는 연계 규칙을 적용하지 않는다.
     */
    public LinkedPlan plan(
            AuthorityRequestService.RequestKind kind,
            Set<String> selectedAuthorityIds,
            String departmentCode) {
        return plan(kind, selectedAuthorityIds, departmentCode, Set.of());
    }

    /** 이미 신청 또는 보유 중인 권한을 반영해 실제로 추가할 연계 권한만 계산한다. */
    public LinkedPlan plan(
            AuthorityRequestService.RequestKind kind,
            Set<String> selectedAuthorityIds,
            String departmentCode,
            Set<String> alreadySatisfiedAuthorityIds) {
        if (kind == null || selectedAuthorityIds == null) {
            throw new IllegalArgumentException("신청 구분과 선택 권한이 필요합니다.");
        }
        if (alreadySatisfiedAuthorityIds == null) {
            throw new IllegalArgumentException("이미 신청 또는 보유 중인 권한 목록이 필요합니다.");
        }
        LinkedHashSet<String> selected = normalized(selectedAuthorityIds);
        LinkedHashSet<String> satisfied = normalized(alreadySatisfiedAuthorityIds);
        Map<String, AuthorizationCatalog.AuthorityDefinition> selectedAuthorities = new LinkedHashMap<>();
        selected.forEach(authorityId -> selectedAuthorities.put(authorityId, requireActive(authorityId)));
        selected.stream().filter(satisfied::contains).findFirst()
                .ifPresent(authorityId -> { throw new ApiException(ErrorCode.CONFLICT, authorityId); });
        LinkedHashSet<String> automatic = new LinkedHashSet<>();
        if (kind == AuthorityRequestService.RequestKind.GRANT) {
            selected.forEach(authorityId -> automatic.addAll(AI_TO_AB.getOrDefault(authorityId, List.of())));
            boolean selectedAiAuthority = selectedAuthorities.values().stream()
                    .anyMatch(authority -> authority.systemId().equals("AI"));
            if (selectedAiAuthority && CY_TARGET_DEPARTMENTS.contains(departmentCode)) {
                automatic.add(CY_OFFICE_CUSTOMER_AUTHORITY);
            }
            automatic.removeAll(selected);
            automatic.forEach(this::requireActive);
            automatic.removeAll(satisfied);
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>(selected);
        expanded.addAll(automatic);
        return new LinkedPlan(immutable(selected), immutable(automatic), immutable(expanded));
    }

    /** plan과 동일한 규칙으로 최종 권한 ID 집합만 반환한다. */
    public Set<String> expand(
            AuthorityRequestService.RequestKind kind,
            Set<String> selectedAuthorityIds,
            String departmentCode) {
        return plan(kind, selectedAuthorityIds, departmentCode).expandedAuthorityIds();
    }

    private LinkedHashSet<String> normalized(Set<String> authorityIds) {
        if (authorityIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("권한 ID가 필요합니다.");
        }
        ArrayList<String> sorted = new ArrayList<>(authorityIds);
        Collections.sort(sorted);
        return new LinkedHashSet<>(sorted);
    }

    private AuthorizationCatalog.AuthorityDefinition requireActive(String authorityId) {
        AuthorizationCatalog.AuthorityDefinition authority = catalog.findAuthority(authorityId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, authorityId));
        if (!authority.active()) {
            throw new ApiException(ErrorCode.CONFLICT, authorityId);
        }
        return authority;
    }

    private static Set<String> immutable(LinkedHashSet<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Map<String, List<String>> linkedRules() {
        LinkedHashMap<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("AI0201002002", List.of("AB0204001002"));
        rules.put("AI0201002003", List.of("AB0204001001"));
        rules.put("AI0201001002", List.of("AB0204001002", "AB0204001001"));
        rules.put("AI0101001001", List.of("AB0102001001"));
        rules.put("AI0102001001", List.of("AB0102001001"));
        return Collections.unmodifiableMap(rules);
    }

    public record LinkedPlan(
            Set<String> selectedAuthorityIds,
            Set<String> automaticallyAddedAuthorityIds,
            Set<String> expandedAuthorityIds) { }
}
