package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 인증 사용자의 현재 조직을 기준으로 실제 효력이 있는 업무 권한을 계산한다.
 * 승인 상태, 적용 기간, 권한 마스터 활성 여부와 위임 원천 권한을 모두 통과해야 결과에 포함된다.
 */
@Service
@com.example.permissiondemo.storage.StateBoundary
public class EffectiveAuthorityService {

    private final AuthorizationCatalog catalog;
    private final Clock clock;

    public EffectiveAuthorityService(AuthorizationCatalog catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * 클라이언트가 보낸 권한 ID가 아니라 인증된 사용자명으로만 유효 권한을 계산한다.
     * 미인증 또는 익명 인증은 권한이 없는 상태로 처리해 fail-closed를 유지한다.
     */
    public Set<String> findEffectiveAuthorityIds(Authentication authentication) {
        if (!isAuthenticatedUser(authentication)) {
            return Set.of();
        }
        return findEffectiveAuthorityIds(authentication.getName());
    }

    /**
     * 사용자명 기준 유효 권한을 계산한다.
     * 현재 조직과 일치하는 최신 이력만 선택한 후 직접 권한과 검증된 위임 권한을 합친다.
     */
    public Set<String> findEffectiveAuthorityIds(String username) {
        LocalDate today = LocalDate.now(clock);
        Optional<AuthorizationCatalog.UserProfile> userProfile = catalog.findUser(username);
        if (userProfile.isEmpty() || !userProfile.get().active()) {
            return Set.of();
        }
        String organizationId = userProfile.get().organizationId();
        Set<String> effectiveIds = new LinkedHashSet<>();
        // 회수·승인 대기·기간 만료·비활성 권한은 합집합을 만들기 전에 제거한다.
        List<AuthorizationCatalog.AuthorityAssignment> effectiveAssignments =
                findLatestAssignments(username, organizationId).stream()
                        .filter(assignment -> assignment.isEffectiveOn(today))
                        .filter(assignment -> isActiveAuthority(assignment.authorityId()))
                        .toList();

        // 직접 권한은 별도 원천 사용자 검증 없이 현재 사용자의 권한으로 인정한다.
        effectiveAssignments.stream()
                .filter(assignment -> assignment.type() == AuthorizationCatalog.AssignmentType.DIRECT)
                .map(AuthorizationCatalog.AuthorityAssignment::authorityId)
                .forEach(effectiveIds::add);

        // 위임 권한은 위임자가 같은 조직에서 해당 권한을 직접 보유하고 있어야 한다.
        effectiveAssignments.stream()
                .filter(assignment -> assignment.type() == AuthorizationCatalog.AssignmentType.DELEGATED)
                .filter(assignment -> hasValidDirectSource(
                        assignment.delegatedBy(), organizationId, assignment.authorityId(), today))
                .map(AuthorizationCatalog.AuthorityAssignment::authorityId)
                .forEach(effectiveIds::add);

        return Set.copyOf(effectiveIds);
    }

    /** 사용자 프로필에서 현재 조직 ID를 조회한다. */
    public Optional<String> findCurrentOrganizationId(String username) {
        return catalog.findUser(username).map(AuthorizationCatalog.UserProfile::organizationId);
    }

    /**
     * 지정 사용자가 현재 조직에서 해당 권한을 직접 보유하는지 확인한다.
     * 권한 위임 등록 시 위임 원천이 또 다른 위임 권한인지 구분하는 데 사용한다.
     */
    public boolean hasDirectAuthority(String username, String authorityId) {
        LocalDate today = LocalDate.now(clock);
        return catalog.findUser(username)
                .filter(AuthorizationCatalog.UserProfile::active)
                .map(AuthorizationCatalog.UserProfile::organizationId)
                .map(organizationId -> hasValidDirectSource(
                        username, organizationId, authorityId, today))
                .orElse(false);
    }

    /**
     * 위임자가 현재 같은 조직에 속하고 해당 권한의 유효한 직접 권한을 보유하는지 확인한다.
     * 위임자가 가진 또 다른 위임 권한을 원천으로 인정하지 않아 연쇄 위임을 차단한다.
     */
    private boolean hasValidDirectSource(
            String delegatedBy,
            String organizationId,
            String authorityId,
            LocalDate today) {
        if (delegatedBy == null || delegatedBy.isBlank()) {
            return false;
        }
        boolean sameCurrentOrganization = catalog.findUser(delegatedBy)
                .filter(AuthorizationCatalog.UserProfile::active)
                .map(AuthorizationCatalog.UserProfile::organizationId)
                .filter(organizationId::equals)
                .isPresent();
        if (!sameCurrentOrganization) {
            return false;
        }
        return findLatestAssignments(delegatedBy, organizationId).stream()
                .filter(assignment -> assignment.type() == AuthorizationCatalog.AssignmentType.DIRECT)
                .filter(assignment -> assignment.authorityId().equals(authorityId))
                .filter(assignment -> assignment.isEffectiveOn(today))
                .anyMatch(assignment -> isActiveAuthority(assignment.authorityId()));
    }

    /**
     * 사용자·조직·권한·부여유형별 sequence가 가장 큰 최신 이력만 반환한다.
     * 과거 승인 이력 뒤에 회수 이력이 있으면 회수 상태가 최종 판단에 사용된다.
     */
    private List<AuthorizationCatalog.AuthorityAssignment> findLatestAssignments(
            String username, String organizationId) {
        Map<AssignmentKey, AuthorizationCatalog.AuthorityAssignment> latestByKey =
                new LinkedHashMap<>();

        catalog.assignmentsFor(username).stream()
                .filter(assignment -> assignment.organizationId().equals(organizationId))
                .forEach(assignment -> latestByKey.merge(
                        new AssignmentKey(assignment.authorityId(), assignment.type()),
                        assignment,
                        (current, candidate) -> candidate.sequence() > current.sequence()
                                ? candidate : current));

        return List.copyOf(latestByKey.values());
    }

    /** 권한 마스터가 존재하며 활성 상태인지 확인한다. */
    private boolean isActiveAuthority(String authorityId) {
        return catalog.findAuthority(authorityId)
                .map(AuthorizationCatalog.AuthorityDefinition::active)
                .orElse(false);
    }

    /** Spring Security 인증 객체가 실제 로그인 사용자 상태인지 확인한다. */
    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** 최신 이력 선택 시 권한 ID와 직접·위임 유형을 구분하는 그룹 키다. */
    private record AssignmentKey(
            String authorityId, AuthorizationCatalog.AssignmentType assignmentType) {
    }
}
