package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 사용자별 명시 전환으로 SSO 직접 권한 범위를 적용한다. 저장 항목이 없는 사용자는 기존 인가를 유지한다. */
@Service
@StateBoundary
public class SsoAuthorityScopeService implements StateParticipant {
    private final Map<String, Mode> modes = new LinkedHashMap<>();
    private final AuthorizationCatalog catalog;
    private final AuthoritySsoHistoryLinkService links;
    private final UserSsoOrganizationService organizations;
    private final OrganizationMappingService mappings;
    private final AuditEventService audit;
    private final Clock clock;

    public SsoAuthorityScopeService(AuthorizationCatalog catalog, AuthoritySsoHistoryLinkService links,
            UserSsoOrganizationService organizations, OrganizationMappingService mappings, AuditEventService audit, Clock clock) {
        this.catalog = catalog; this.links = links; this.organizations = organizations;
        this.mappings = mappings; this.audit = audit; this.clock = clock;
    }

    public boolean isEnabled(String username) { return modes.containsKey(username) && modes.get(username).enabled(); }

    public List<Mode> list() {
        return catalog.users().stream().map(user -> modes.getOrDefault(user.username(), new Mode(user.username(), false, 0)))
                .sorted(Comparator.comparing(Mode::username)).toList();
    }

    public Mode save(WriteMode value) {
        if (value == null || value.username() == null || value.username().isBlank()) throw new IllegalArgumentException("사용자를 선택해 주세요.");
        if (catalog.findUser(value.username()).isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "사용자");
        Mode old = modes.getOrDefault(value.username(), new Mode(value.username(), false, 0));
        if (old.version() != value.expectedVersion()) throw new ApiException(ErrorCode.CONFLICT, "SSO 인가 설정이 변경되었습니다.");
        if (value.enabled()) {
            Scope scope = requireCurrentScope(value.username());
            if ("admin".equals(value.username()) && latest("admin", scope, null).stream()
                    .noneMatch(item -> AuthorizationCatalog.AUTH_SYSTEM_ADMIN.equals(item.authorityId())
                            && item.isEffectiveOn(LocalDate.now(clock))
                            && catalog.findAuthority(item.authorityId()).map(AuthorizationCatalog.AuthorityDefinition::active).orElse(false))) {
                throw new ApiException(ErrorCode.CONFLICT, "현재 SSO소속의 유효한 직접 관리자 권한을 먼저 연결해 주세요.");
            }
        }
        Mode saved = new Mode(value.username(), value.enabled(), old.version() + 1);
        modes.put(saved.username(), saved);
        if (old.enabled() != saved.enabled()) catalog.touchAuthorityVersion();
        audit.record("SSO_AUTHORIZATION_MODE_SAVED", "SSO_AUTHORIZATION_MODE", saved.username(), "SUCCESS", Map.of("enabled", saved.enabled()));
        return saved;
    }

    public Optional<Scope> currentScope(String username) {
        return organizations.list().stream().filter(item -> item.username().equals(username))
                .map(item -> new Scope(item.ssoOfficeCode(), item.ssoDepartmentCode())).filter(this::validScope).findFirst();
    }

    public Scope requireCurrentScope(String username) {
        return currentScope(username).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "사용 가능한 SSO소속이 필요합니다."));
    }

    /** 로컬 조직 ID와 무관하게 명시 SSO소속별 최신 직접 이력을 고른다. 상태·기간 검사는 호출자가 수행한다. */
    public List<AuthorityAssignment> latest(String username, Scope scope, String systemId) {
        if (scope == null || scope.ssoOfficeCode() == null || scope.ssoDepartmentCode() == null) return List.of();
        Map<String, Candidate> latest = new LinkedHashMap<>();
        var assignments = catalog.assignmentsFor(username);
        Comparator<AuthoritySsoHistoryLinkService.Link> order = Comparator.comparing(AuthoritySsoHistoryLinkService.Link::registeredAt)
                .thenComparingLong(AuthoritySsoHistoryLinkService.Link::assignmentSequence);
        for (var link : links.list()) {
            if (link.assignmentType() != AssignmentType.DIRECT || !link.username().equals(username)
                    || !scope.equals(new Scope(link.ssoOfficeCode(), link.ssoDepartmentCode()))) continue;
            if (systemId != null && !systemId.isBlank() && catalog.findAuthority(link.authorityId())
                    .filter(authority -> systemId.equals(authority.systemId())).isEmpty()) continue;
            AuthorityAssignment assignment = assignments.stream().filter(item -> matches(item, link)).findFirst().orElse(null);
            if (assignment == null) continue;
            Candidate previous = latest.get(assignment.authorityId());
            int compared = previous == null ? 1 : order.compare(link, previous.link());
            if (compared == 0 && !assignment.equals(previous.assignment())) {
                throw new ApiException(ErrorCode.CONFLICT, "최신 권한 이력의 등록시각과 순번이 중복됩니다. SSO 연결을 확인해 주세요.");
            }
            if (compared > 0) latest.put(assignment.authorityId(), new Candidate(assignment, link));
        }
        return latest.values().stream().map(Candidate::assignment).sorted(Comparator.comparing(AuthorityAssignment::authorityId)).toList();
    }

    public Optional<Scope> scopeOf(AuthorityAssignment assignment) {
        return findLink(assignment).map(link -> new Scope(link.ssoOfficeCode(), link.ssoDepartmentCode()));
    }

    /** 워크플로가 확정한 소속으로만 연결한다. 기존 연결을 다른 소속으로 자동 덮어쓰지 않는다. */
    public void record(AuthorityAssignment assignment, Scope scope, Instant registeredAt) {
        if (!catalog.assignmentsFor(assignment.username()).contains(assignment)) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "권한 이력");
        boolean knownHistory = scope != null && links.list().stream().anyMatch(link -> link.username().equals(assignment.username())
                && scope.equals(new Scope(link.ssoOfficeCode(), link.ssoDepartmentCode())));
        if (assignment.type() != AssignmentType.DIRECT || !validScope(scope) && !knownHistory) throw new ApiException(ErrorCode.CONFLICT, "직접 권한과 확인된 SSO소속이 필요합니다.");
        var existing = findLink(assignment);
        if (existing.isPresent()) {
            if (!scope.equals(new Scope(existing.get().ssoOfficeCode(), existing.get().ssoDepartmentCode()))) {
                throw new ApiException(ErrorCode.CONFLICT, "권한 이력에 이미 다른 SSO소속이 연결되어 있습니다.");
            }
            return;
        }
        links.recordInScope(assignment, scope.ssoOfficeCode(), scope.ssoDepartmentCode(), registeredAt);
    }

    public void requireLatest(AuthorityAssignment assignment, Scope scope) {
        if (assignment.type() != AssignmentType.DIRECT || latest(assignment.username(), scope, null).stream().noneMatch(assignment::equals)) {
            throw new ApiException(ErrorCode.CONFLICT, "SSO소속의 최신 직접 권한 이력이 변경되었습니다.");
        }
    }

    /** 활성 인가 사용자에게 영향을 주는 소속·이력 변경만 전역 권한 버전을 갱신한다. */
    public void invalidateUser(String username) { if (isEnabled(username)) catalog.touchAuthorityVersion(); }

    public void invalidateMapping(String ssoOfficeCode, String ssoDepartmentCode) {
        if (organizations.list().stream().anyMatch(item -> isEnabled(item.username())
                && item.ssoOfficeCode().equals(ssoOfficeCode) && item.ssoDepartmentCode().equals(ssoDepartmentCode))) catalog.touchAuthorityVersion();
    }

    private Optional<AuthoritySsoHistoryLinkService.Link> findLink(AuthorityAssignment assignment) {
        return links.list().stream().filter(link -> matches(assignment, link)).findFirst();
    }

    private boolean matches(AuthorityAssignment assignment, AuthoritySsoHistoryLinkService.Link link) {
        return assignment.username().equals(link.username()) && assignment.organizationId().equals(link.organizationId())
                && assignment.authorityId().equals(link.authorityId()) && assignment.type() == link.assignmentType()
                && assignment.sequence() == link.assignmentSequence();
    }

    private boolean validScope(Scope scope) {
        return scope != null && scope.ssoOfficeCode() != null && scope.ssoDepartmentCode() != null
                && mappings.list().stream().anyMatch(item -> item.active() && item.ssoOfficeCode().equals(scope.ssoOfficeCode())
                        && item.ssoDepartmentCode().equals(scope.ssoDepartmentCode()));
    }

    private record Candidate(AuthorityAssignment assignment, AuthoritySsoHistoryLinkService.Link link) { }
    public record Scope(String ssoOfficeCode, String ssoDepartmentCode) { }
    public record Mode(String username, boolean enabled, long version) { }
    public record WriteMode(String username, boolean enabled, long expectedVersion) { }
    public record StoredState(List<Mode> modes) { }
    @Override public String stateKey() { return "sso-authorizations"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(List.copyOf(modes.values())); }
    @Override public void restoreState(Object raw) {
        modes.clear();
        if (raw == null || ((StoredState) raw).modes() == null) return;
        ((StoredState) raw).modes().forEach(item -> modes.put(item.username(), item));
    }
}
