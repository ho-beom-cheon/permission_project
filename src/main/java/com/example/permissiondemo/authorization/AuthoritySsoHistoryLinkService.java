package com.example.permissiondemo.authorization;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 직접 권한 이력의 SSO소속·등록시각을 별도로 보존한다. 미확인 과거 소속은 추정하지 않는다. */
@Service
@StateBoundary
public class AuthoritySsoHistoryLinkService implements StateParticipant {
    private final Map<AssignmentKey, Link> links = new LinkedHashMap<>();
    private final AuthorizationCatalog catalog;
    private final OrganizationMappingService mappings;
    private final ObjectProvider<UserSsoOrganizationService> organizations;
    private final AuditEventService audit;
    private ObjectProvider<SsoAuthorityScopeService> scopes;

    @Autowired
    public void configureScopeInvalidation(ObjectProvider<SsoAuthorityScopeService> scopes) { this.scopes = scopes; }

    public AuthoritySsoHistoryLinkService(AuthorizationCatalog catalog, OrganizationMappingService mappings,
            ObjectProvider<UserSsoOrganizationService> organizations, AuditEventService audit) {
        this.catalog = catalog; this.mappings = mappings; this.organizations = organizations; this.audit = audit;
    }

    public List<Link> list() {
        return links.values().stream().sorted(Comparator.comparing(Link::registeredAt)
                .thenComparingLong(Link::assignmentSequence).reversed()).toList();
    }

    public Link save(WriteLink value) {
        if (value == null || value.assignmentType() != AssignmentType.DIRECT || value.registeredAt() == null) {
            throw new IllegalArgumentException("직접 권한 이력과 등록시각을 확인해 주세요.");
        }
        AssignmentKey key = new AssignmentKey(value.username(), value.organizationId(), value.authorityId(), value.assignmentType(), value.assignmentSequence());
        requireAssignment(key);
        if (!activeMapping(value.ssoOfficeCode(), value.ssoDepartmentCode())) {
            throw new ApiException(ErrorCode.CONFLICT, "사용 중인 SSO 조직 대응을 선택해 주세요.");
        }
        Link old = links.get(key);
        if (value.expectedVersion() != (old == null ? 0 : old.version())) {
            throw new ApiException(ErrorCode.CONFLICT, "권한 이력의 SSO 연결이 변경되었습니다. 다시 조회해 주세요.");
        }
        Link saved = new Link(key.username(), key.organizationId(), key.authorityId(), key.assignmentType(), key.assignmentSequence(),
                value.ssoOfficeCode(), value.ssoDepartmentCode(), value.registeredAt(), value.expectedVersion() + 1);
        links.put(key, saved);
        if (old == null || !old.ssoOfficeCode().equals(saved.ssoOfficeCode()) || !old.ssoDepartmentCode().equals(saved.ssoDepartmentCode())
                || !old.registeredAt().equals(saved.registeredAt())) invalidate(saved.username());
        audit.record("AUTHORITY_SSO_HISTORY_LINK_SAVED", "AUTHORITY_SSO_HISTORY_LINK", key.username(), "SUCCESS", Map.of());
        return saved;
    }

    /** 새 신청은 현재 명시 소속, 후속 처리는 직전 이력의 확정 소속만 이어받는다. */
    public void capture(AuthorityAssignment assignment, Instant registeredAt, Long predecessorSequence) {
        if (assignment.type() != AssignmentType.DIRECT) return;
        AssignmentKey key = key(assignment);
        if (links.containsKey(key)) return;
        requireAssignment(key);
        if (registeredAt == null) throw new IllegalArgumentException("권한 이력 등록시각이 필요합니다.");
        String ssoOffice;
        String ssoDepartment;
        if (predecessorSequence != null) {
            Link previous = links.get(new AssignmentKey(assignment.username(), assignment.organizationId(), assignment.authorityId(),
                    assignment.type(), predecessorSequence));
            if (previous == null) return;
            ssoOffice = previous.ssoOfficeCode(); ssoDepartment = previous.ssoDepartmentCode();
        } else {
            var affiliation = organizations.getObject().list().stream()
                    .filter(item -> item.username().equals(assignment.username())).findFirst().orElse(null);
            if (affiliation == null || !activeMapping(affiliation.ssoOfficeCode(), affiliation.ssoDepartmentCode())) return;
            ssoOffice = affiliation.ssoOfficeCode(); ssoDepartment = affiliation.ssoDepartmentCode();
        }
        links.put(key, new Link(key.username(), key.organizationId(), key.authorityId(), key.assignmentType(), key.assignmentSequence(),
                ssoOffice, ssoDepartment, registeredAt, 1));
        invalidate(assignment.username());
    }

    /** 확정된 워크플로 소속을 보존한다. 과거 대응이 비활성화돼도 이미 확인된 소속을 바꾸지 않는다. */
    public void recordInScope(AuthorityAssignment assignment, String ssoOfficeCode, String ssoDepartmentCode, Instant registeredAt) {
        if (assignment.type() != AssignmentType.DIRECT || ssoOfficeCode == null || ssoOfficeCode.isBlank()
                || ssoDepartmentCode == null || ssoDepartmentCode.isBlank() || registeredAt == null) {
            throw new IllegalArgumentException("직접 권한 이력의 확정 SSO소속과 등록시각이 필요합니다.");
        }
        AssignmentKey key = key(assignment);
        requireAssignment(key);
        Link old = links.get(key);
        if (old != null) {
            if (!old.ssoOfficeCode().equals(ssoOfficeCode) || !old.ssoDepartmentCode().equals(ssoDepartmentCode)) {
                throw new ApiException(ErrorCode.CONFLICT, "권한 이력에 다른 SSO소속이 연결되어 있습니다.");
            }
            return;
        }
        links.put(key, new Link(key.username(), key.organizationId(), key.authorityId(), key.assignmentType(), key.assignmentSequence(),
                ssoOfficeCode, ssoDepartmentCode, registeredAt, 1));
        invalidate(assignment.username());
    }

    private void invalidate(String username) {
        if (scopes != null) {
            var service = scopes.getIfAvailable();
            if (service != null) service.invalidateUser(username);
        }
    }

    private boolean activeMapping(String office, String department) {
        return mappings.list().stream().anyMatch(item -> item.active() && item.ssoOfficeCode().equals(office) && item.ssoDepartmentCode().equals(department));
    }

    private void requireAssignment(AssignmentKey key) {
        if (key.username() == null || catalog.assignmentsFor(key.username()).stream().noneMatch(item -> key(item).equals(key))) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "권한 이력을 찾을 수 없습니다.");
        }
    }

    private AssignmentKey key(AuthorityAssignment assignment) {
        return new AssignmentKey(assignment.username(), assignment.organizationId(), assignment.authorityId(), assignment.type(), assignment.sequence());
    }

    private record AssignmentKey(String username, String organizationId, String authorityId, AssignmentType assignmentType, long assignmentSequence) { }
    public record Link(String username, String organizationId, String authorityId, AssignmentType assignmentType, long assignmentSequence,
            String ssoOfficeCode, String ssoDepartmentCode, Instant registeredAt, long version) { }
    public record WriteLink(String username, String organizationId, String authorityId, AssignmentType assignmentType, long assignmentSequence,
            String ssoOfficeCode, String ssoDepartmentCode, Instant registeredAt, long expectedVersion) { }
    public record StoredState(List<Link> links) { }

    @Override public String stateKey() { return "authority-sso-history-links"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(list()); }
    @Override public void restoreState(Object raw) {
        links.clear();
        if (raw == null || ((StoredState) raw).links() == null) return;
        ((StoredState) raw).links().forEach(item -> links.put(new AssignmentKey(item.username(), item.organizationId(), item.authorityId(), item.assignmentType(), item.assignmentSequence()), item));
    }
}
