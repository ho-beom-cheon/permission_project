package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.directory.OrganizationMappingService;
import com.example.permissiondemo.directory.UserSsoOrganizationService;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 권한 부여/변경 신청과 승인·반려를 처리한다.
 * 변경 승인 시 같은 시스템·현재 조직의 직접 권한을 회수한 뒤 신청 권한을 부여한다.
 * 카탈로그·알림과 함께 신규 로컬 DB에 저장하며 원본 DB와 연결하지 않는다.
 */
@Service
@com.example.permissiondemo.storage.StateBoundary
public class AuthorityRequestService implements com.example.permissiondemo.storage.StateParticipant {
    private static final LocalDate SOURCE_END = LocalDate.of(9999, 12, 31);
    private final AuthorizationCatalog catalog;
    private final AuditEventService audit;
    private final Clock clock;
    private final Map<Long, AuthorityRequest> requests = new LinkedHashMap<>();
    private final Map<Long, List<AuthorityAssignment>> baselines = new HashMap<>();
    private long sequence;
    private final com.example.permissiondemo.common.InboxService inbox;
    private AuthorityHistoryService history;
    private SsoAuthorityScopeService scopes;
    private UserSsoOrganizationService userSsoOrganizations;
    private OrganizationMappingService organizationMappings;

    @org.springframework.beans.factory.annotation.Autowired
    public void setHistory(AuthorityHistoryService history) { this.history = history; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setScopes(SsoAuthorityScopeService scopes) { this.scopes = scopes; }

    @org.springframework.beans.factory.annotation.Autowired
    public void setUserSsoOrganizations(UserSsoOrganizationService userSsoOrganizations) {
        this.userSsoOrganizations = userSsoOrganizations;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setOrganizationMappings(OrganizationMappingService organizationMappings) {
        this.organizationMappings = organizationMappings;
    }

    public AuthorityRequestService(AuthorizationCatalog catalog, AuditEventService audit, Clock clock) {
        this(catalog,audit,clock,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthorityRequestService(AuthorizationCatalog catalog, AuditEventService audit, Clock clock,
            com.example.permissiondemo.common.InboxService inbox) {
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
        this.inbox = inbox;
    }

    /** 신청자는 컨트롤러의 인증 컨텍스트에서 정하며 요청 JSON의 사용자·조직은 사용하지 않는다. */
    public AuthorityRequest submit(String username, RequestKind kind, Set<String> authorityIds,
            LocalDate validFrom, LocalDate validTo, String reason) {
        synchronized (catalog) {
            var user = catalog.findUser(username)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
            if (kind == null || authorityIds == null || authorityIds.isEmpty() || authorityIds.size() > 100) {
                throw new IllegalArgumentException("신청 구분과 1~100개의 권한을 선택해 주세요.");
            }
            if (kind == RequestKind.CHANGE && authorityIds.size() != 1) {
                throw new IllegalArgumentException("변경할 권한을 한 개 선택해 주세요.");
            }
            String cleanReason = requiredReason(reason);
            List<String> ids = authorityIds.stream().sorted().toList();
            String systemId = requireActiveAuthority(ids.get(0)).systemId();
            for (String id : ids) {
                if (!requireActiveAuthority(id).systemId().equals(systemId)) {
                    throw new IllegalArgumentException("한 신청에는 같은 시스템의 권한만 선택할 수 있습니다.");
                }
            }
            boolean useSso = scopes != null && scopes.isEnabled(username);
            SsoAuthorityScopeService.Scope scope = useSso ? scopes.requireCurrentScope(username) : null;
            int workflowVersion = useSso ? 3 : 2;
            boolean pending = requests.values().stream().anyMatch(item -> item.username().equals(username)
                    && item.organizationId().equals(user.organizationId()) && item.systemId().equals(systemId)
                    && item.status() == RequestStatus.PENDING && item.workflowVersion() != workflowVersion);
            if (pending) {
                throw new ApiException(ErrorCode.CONFLICT, "이미 처리 대기 중인 신청이 있습니다.");
            }
            List<AuthorityAssignment> baseline = useSso ? scopes.latest(username, scope, systemId)
                    : latestDirectAssignments(username, user.organizationId(), systemId);
            LocalDate today = LocalDate.now(clock);
            // 원본 부여·변경 신청 모두 최신 신청 또는 현재 유효한 승인 대상은 다시 신청하지 않는다.
            if (baseline.stream().anyMatch(item -> ids.contains(item.authorityId())
                    && (item.isEffectiveOn(today) || item.status() == AssignmentStatus.PENDING))) {
                throw new ApiException(ErrorCode.CONFLICT, "이미 승인되어 현재 유효한 권한이 포함되어 있습니다.");
            }
            Instant requestedAt = Instant.now(clock);
            List<AuthorityAssignment> pendingAssignments = ids.stream().map(id -> {
                if (!useSso) return catalog.createPendingAssignment(username, user.organizationId(), id, today, SOURCE_END);
                var assignment = catalog.createScopedAssignment(username, user.organizationId(), id, AssignmentStatus.PENDING, today, SOURCE_END);
                scopes.record(assignment, scope, requestedAt);
                return assignment;
            }).toList();
            AuthorityRequest request = new AuthorityRequest(++sequence, username, user.organizationId(),
                    systemId, kind, ids, today, SOURCE_END, cleanReason, RequestStatus.PENDING,
                    requestedAt, null, null, null, workflowVersion,
                    scope == null ? null : scope.ssoOfficeCode(), scope == null ? null : scope.ssoDepartmentCode());
            requests.put(request.id(), request);
            baselines.put(request.id(), pendingAssignments);
            if (history != null) history.recordRequest(request, pendingAssignments);
            audit.record("AUTHORITY_REQUESTED", "AUTHORITY_REQUEST", String.valueOf(request.id()),
                    "SUCCESS", Map.of("kind", kind.name(), "count", ids.size()));
            return request;
        }
    }

    /** 현재 로컬 또는 SSO 범위에서 유효하거나 처리 대기 중인 직접 권한 ID를 반환한다. */
    public Set<String> satisfiedAuthorityIds(String username) {
        synchronized (catalog) {
            var user = catalog.findUser(username)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
            LocalDate today = LocalDate.now(clock);
            boolean useSso = scopes != null && scopes.isEnabled(username);
            SsoAuthorityScopeService.Scope scope = useSso ? scopes.requireCurrentScope(username) : null;
            List<AuthorityAssignment> latest;
            if (useSso) {
                latest = scopes.latest(username, scope, null);
            } else {
                Map<String, AuthorityAssignment> byAuthority = new HashMap<>();
                catalog.assignmentsFor(username).stream()
                        .filter(item -> item.organizationId().equals(user.organizationId())
                                && item.type() == AssignmentType.DIRECT)
                        .forEach(item -> byAuthority.merge(item.authorityId(), item,
                                (a, b) -> a.sequence() > b.sequence() ? a : b));
                latest = List.copyOf(byAuthority.values());
            }
            Set<String> result = new TreeSet<>();
            latest.stream().filter(item -> item.status() == AssignmentStatus.PENDING || item.isEffectiveOn(today))
                    .map(AuthorityAssignment::authorityId).forEach(result::add);
            requests.values().stream()
                    .filter(item -> item.username().equals(username) && item.status() == RequestStatus.PENDING)
                    .filter(item -> useSso
                            ? item.workflowVersion() == 3
                                    && scope.ssoOfficeCode().equals(item.ssoOfficeCode())
                                    && scope.ssoDepartmentCode().equals(item.ssoDepartmentCode())
                            : item.workflowVersion() < 3 && user.organizationId().equals(item.organizationId()))
                    .flatMap(item -> item.authorityIds().stream()).forEach(result::add);
            return Set.copyOf(result);
        }
    }

    /** 사용자 자신의 신청 또는 관리 권한으로 허용된 전체 신청 목록을 페이지 단위로 반환한다. */
    public PageResult<AuthorityRequest> list(String username, RequestStatus status, PageQuery query) {
        synchronized (catalog) {
            return PageResult.of(requests.values().stream()
                    .filter(item -> username == null || item.username().equals(username))
                    .filter(item -> status == null || item.status() == status)
                    .sorted(Comparator.comparingLong(AuthorityRequest::id).reversed()).toList(), query);
        }
    }

    /** 심사자의 본부 또는 로컬 사업소 범위를 신청 버전에 맞춰 목록에 적용한다. */
    public PageResult<AuthorityRequest> listForReview(String reviewer, RequestStatus status, PageQuery query) {
        synchronized (catalog) {
            ReviewScope scope = reviewScope(reviewer);
            return PageResult.of(requests.values().stream()
                    .filter(item -> canReview(scope, item))
                    .filter(item -> status == null || item.status() == status)
                    .sorted(Comparator.comparingLong(AuthorityRequest::id).reversed()).toList(), query);
        }
    }

    /**
     * 일괄 처리 전에 모든 신청을 검증한다. 신청 당시 권한이 바뀐 경우 승인으로 덮어쓰지 않는다.
     * 카탈로그의 동일 모니터를 사용해 직접 부여·회수와 승인 처리가 교차하지 않게 한다.
     */
    public List<AuthorityRequest> review(String reviewer, List<Long> requestIds, Decision decision, String reason) {
        return review(reviewer, requestIds, decision, reason, false);
    }

    /** 관리자 심사 API에서 사용하는 범위 강제 처리. 범위 밖 신청이 섞이면 변경 전에 전체를 거부한다. */
    public List<AuthorityRequest> reviewForReviewer(String reviewer, List<Long> requestIds, Decision decision, String reason) {
        return review(reviewer, requestIds, decision, reason, true);
    }

    private List<AuthorityRequest> review(String reviewer, List<Long> requestIds, Decision decision, String reason,
            boolean enforceReviewScope) {
        synchronized (catalog) {
            if (decision == null || requestIds == null || requestIds.isEmpty() || requestIds.size() > 100
                    || requestIds.stream().anyMatch(Objects::isNull)
                    || requestIds.stream().distinct().count() != requestIds.size()) {
                throw new IllegalArgumentException("처리할 신청을 중복 없이 1~100개 선택해 주세요.");
            }
            String reviewReason = decision == Decision.REJECT ? requiredReason(reason) : optionalReason(reason);
            ReviewScope reviewScope = enforceReviewScope ? reviewScope(reviewer) : null;
            List<AuthorityRequest> selected = requestIds.stream().map(id -> {
                AuthorityRequest request = requests.get(id);
                if (request == null) {
                    throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id);
                }
                if (request.status() != RequestStatus.PENDING) {
                    throw new ApiException(ErrorCode.CONFLICT, id);
                }
                if (reviewScope != null && !canReview(reviewScope, request)) {
                    throw new ApiException(ErrorCode.ACCESS_DENIED, "소속 사업소의 권한 신청만 처리할 수 있습니다.");
                }
                if (request.workflowVersion() >= 2) validatePendingAssignments(request);
                if (decision == Decision.APPROVE) {
                    validateApproval(request);
                }
                return request;
            }).toList();
            LocalDate today = LocalDate.now(clock);
            // 같은 사용자의 같은 시스템에 변경신청이 여러 개 있어도 신규 승인 전에 한 번만 회수한다.
            if (decision == Decision.APPROVE) {
                Map<ReviewGroup, AuthorityRequest> changeGroups = new LinkedHashMap<>();
                selected.stream().filter(r -> r.workflowVersion() >= 2 && r.kind() == RequestKind.CHANGE)
                        .forEach(r -> changeGroups.putIfAbsent(new ReviewGroup(r.username(), r.workflowVersion() == 3 ? null : r.organizationId(),
                                r.systemId(), r.ssoOfficeCode(), r.ssoDepartmentCode()), r));
                for (AuthorityRequest change : changeGroups.values()) {
                    List<AuthorityAssignment> existing = change.workflowVersion() == 3
                            ? scopes.latest(change.username(), requestScope(change), change.systemId())
                            : latestDirectAssignments(change.username(), change.organizationId(), change.systemId());
                    for (AuthorityAssignment assignment : existing) {
                        if (!assignment.isEffectiveOn(today)) continue;
                        AuthorityAssignment revoked;
                        if (change.workflowVersion() == 3) {
                            var changeScope = requestScope(change);
                            scopes.requireLatest(assignment, changeScope);
                            revoked = catalog.processScopedAssignment(assignment, AssignmentStatus.REVOKED, assignment.validFrom(), today);
                            scopes.record(revoked, changeScope, Instant.now(clock));
                        } else {
                            revoked = catalog.revokeForChange(assignment, today);
                        }
                        if (history != null) history.recordAssignment(revoked, RequestKind.CHANGE, change.id(), reviewer, "권한변경신청");
                    }
                }
            }
            List<AuthorityRequest> result = new ArrayList<>();
            for (AuthorityRequest request : selected) {
                List<AuthorityAssignment> approvedAssignments = new ArrayList<>();
                LocalDate processedFrom = request.validFrom();
                LocalDate processedTo = request.validTo();
                if (request.workflowVersion() >= 2) {
                    processedFrom = today;
                    processedTo = decision == Decision.APPROVE ? SOURCE_END : today;
                    for (AuthorityAssignment pendingAssignment : baselines.get(request.id())) {
                        AssignmentStatus nextStatus = decision == Decision.APPROVE ? AssignmentStatus.APPROVED : AssignmentStatus.REJECTED;
                        if (request.workflowVersion() == 3) {
                            var requestScope = requestScope(request);
                            scopes.requireLatest(pendingAssignment, requestScope);
                            var processed = catalog.processScopedAssignment(pendingAssignment, nextStatus, processedFrom, processedTo);
                            scopes.record(processed, requestScope, Instant.now(clock));
                            approvedAssignments.add(processed);
                        } else {
                            approvedAssignments.add(catalog.processPendingAssignment(pendingAssignment, nextStatus, processedFrom, processedTo));
                        }
                    }
                } else if (decision == Decision.APPROVE) {
                    if (request.kind() == RequestKind.CHANGE) {
                        latestDirectAssignments(request.username(), request.organizationId(), request.systemId())
                                .stream().filter(item -> item.status() == AssignmentStatus.APPROVED)
                                .forEach(item -> {
                                    var revoked = catalog.revokeAssignment(item.username(), item.authorityId(), AssignmentType.DIRECT);
                                    if (history != null) history.recordAssignment(revoked, RequestKind.CHANGE,
                                            request.id(), reviewer, "권한변경신청");
                                });
                    }
                    for (String id : request.authorityIds()) {
                        approvedAssignments.add(catalog.saveAssignment(request.username(), id, AssignmentType.DIRECT,
                                request.validFrom(), request.validTo(), null));
                    }
                }
                RequestStatus status = decision == Decision.APPROVE ? RequestStatus.APPROVED : RequestStatus.REJECTED;
                AuthorityRequest reviewed = new AuthorityRequest(request.id(), request.username(), request.organizationId(),
                        request.systemId(), request.kind(), request.authorityIds(), processedFrom, processedTo,
                        request.reason(), status, request.requestedAt(), reviewer, Instant.now(clock), reviewReason, request.workflowVersion(),
                        request.ssoOfficeCode(), request.ssoDepartmentCode());
                requests.put(request.id(), reviewed);
                baselines.remove(request.id());
                if (history != null) history.recordReview(reviewed, approvedAssignments);
                if(inbox!=null)inbox.deliver(request.username(),"AUTHORITY_REVIEW:"+request.id(),
                        decision==Decision.APPROVE?"권한 신청이 승인되었습니다.":"권한 신청이 반려되었습니다.",
                        "신청 번호 "+request.id()+" · "+request.systemId()+" · "+reviewReason,"/authority-requests.html");
                audit.record("AUTHORITY_REQUEST_" + status.name(), "AUTHORITY_REQUEST", String.valueOf(request.id()),
                        "SUCCESS", Map.of("kind", request.kind().name(), "count", request.authorityIds().size()));
                result.add(reviewed);
            }
            return List.copyOf(result);
        }
    }

    private ReviewScope reviewScope(String reviewer) {
        var user = catalog.findUser(reviewer)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, reviewer));
        boolean global = "admin".equals(reviewer)
                || userSsoOrganizations != null && userSsoOrganizations.hasHeadquartersScope(reviewer);
        String ssoLocalOffice = userSsoOrganizations == null || organizationMappings == null ? null
                : userSsoOrganizations.find(reviewer)
                        .flatMap(item -> organizationMappings.findActive(item.ssoOfficeCode(), item.ssoDepartmentCode()))
                        .map(OrganizationMappingService.Mapping::officeCode)
                        .filter(office -> !office.isBlank()).orElse(null);
        return new ReviewScope(global, user.organizationId(), ssoLocalOffice);
    }

    private boolean canReview(ReviewScope reviewer, AuthorityRequest request) {
        if (reviewer.global()) return true;
        if (request.workflowVersion() == 3) {
            if (reviewer.ssoLocalOffice() == null || organizationMappings == null) return false;
            return organizationMappings.findActive(request.ssoOfficeCode(), request.ssoDepartmentCode())
                    .map(OrganizationMappingService.Mapping::officeCode)
                    .filter(office -> !office.isBlank())
                    .filter(reviewer.ssoLocalOffice()::equals).isPresent();
        }
        return reviewer.organizationId().equals(request.organizationId());
    }

    private record ReviewScope(boolean global, String organizationId, String ssoLocalOffice) { }

    private void validateApproval(AuthorityRequest request) {
        var user = catalog.findUser(request.username()).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, request.id()));
        if (!user.active() || (request.workflowVersion() < 3 && !user.organizationId().equals(request.organizationId()))
                || (request.workflowVersion() < 2 && !latestDirectAssignments(user.username(), user.organizationId(), request.systemId())
                        .equals(baselines.get(request.id())))) {
            throw new ApiException(ErrorCode.CONFLICT, "신청 이후 사용자 조직 또는 권한이 변경됐습니다.");
        }
        if (request.workflowVersion() < 2) validatePeriod(request.validFrom(), request.validTo());
        for (String id : request.authorityIds()) {
            if (!requireActiveAuthority(id).systemId().equals(request.systemId())) {
                throw new ApiException(ErrorCode.CONFLICT, id);
            }
        }
        // 관리 진입을 유지하는 기존 가상 관리자 계정의 필수 권한을 변경 승인으로 제거하지 않는다.
        if (request.username().equals("admin") && request.kind() == RequestKind.CHANGE
                && request.systemId().equals("INFO")
                && !request.authorityIds().contains(AuthorizationCatalog.AUTH_SYSTEM_ADMIN)) {
            throw new ApiException(ErrorCode.CONFLICT, AuthorizationCatalog.AUTH_SYSTEM_ADMIN);
        }
    }

    private void validatePendingAssignments(AuthorityRequest request) {
        List<AuthorityAssignment> expected = baselines.get(request.id());
        if (expected == null || expected.size() != request.authorityIds().size()
                || !expected.stream().map(AuthorityAssignment::authorityId).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.copyOf(request.authorityIds()))) {
            throw new ApiException(ErrorCode.CONFLICT, "신청 이력 정보가 일치하지 않습니다.");
        }
        for (AuthorityAssignment assignment : expected) {
            if (request.workflowVersion() == 3) {
                if (assignment.type() != AssignmentType.DIRECT || assignment.status() != AssignmentStatus.PENDING
                        || !assignment.username().equals(request.username()) || !assignment.organizationId().equals(request.organizationId())) {
                    throw new ApiException(ErrorCode.CONFLICT, "신청 이력 정보가 일치하지 않습니다.");
                }
                scopes.requireLatest(assignment, requestScope(request));
                continue;
            }
            var latest = catalog.assignmentsFor(request.username()).stream()
                    .filter(a -> a.organizationId().equals(request.organizationId()) && a.type() == AssignmentType.DIRECT
                            && a.authorityId().equals(assignment.authorityId()))
                    .max(Comparator.comparingLong(AuthorityAssignment::sequence)).orElse(null);
            if (assignment.type() != AssignmentType.DIRECT || assignment.status() != AssignmentStatus.PENDING
                    || !assignment.username().equals(request.username()) || !assignment.organizationId().equals(request.organizationId())
                    || !assignment.equals(latest)) {
                throw new ApiException(ErrorCode.CONFLICT, "신청 이후 권한 이력이 변경됐습니다.");
            }
        }
    }

    private SsoAuthorityScopeService.Scope requestScope(AuthorityRequest request) {
        if (scopes == null || request.ssoOfficeCode() == null || request.ssoDepartmentCode() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "신청 당시 SSO 소속을 확인할 수 없습니다.");
        }
        return new SsoAuthorityScopeService.Scope(request.ssoOfficeCode(), request.ssoDepartmentCode());
    }

    private record ReviewGroup(String username, String organizationId, String systemId, String ssoOfficeCode, String ssoDepartmentCode) { }

    private AuthorizationCatalog.AuthorityDefinition requireActiveAuthority(String id) {
        var authority = catalog.findAuthority(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id));
        if (!authority.active()) {
            throw new ApiException(ErrorCode.CONFLICT, id);
        }
        return authority;
    }

    private List<AuthorityAssignment> latestDirectAssignments(String username, String organizationId, String systemId) {
        Map<String, AuthorityAssignment> latest = new HashMap<>();
        catalog.assignmentsFor(username).stream()
                .filter(item -> item.organizationId().equals(organizationId) && item.type() == AssignmentType.DIRECT)
                .filter(item -> catalog.findAuthority(item.authorityId()).map(a -> a.systemId().equals(systemId)).orElse(false))
                .forEach(item -> latest.merge(item.authorityId(), item,
                        (a, b) -> a.sequence() > b.sequence() ? a : b));
        return latest.values().stream().sorted(Comparator.comparing(AuthorityAssignment::authorityId)).toList();
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.isBefore(LocalDate.now(clock))) {
            throw new IllegalArgumentException("유효한 시작일·종료일을 입력해 주세요. 이미 만료된 기간은 승인할 수 없습니다.");
        }
    }

    private String requiredReason(String value) {
        String normalized = optionalReason(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("신청 또는 반려 사유를 입력해 주세요.");
        }
        return normalized;
    }

    private String optionalReason(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("사유는 1000자 이하여야 합니다.");
        }
        return normalized;
    }

    /** 원본 요청 구분 GRNT(10), CHNG(20)에 대응한다. */
    public enum RequestKind { GRANT, CHANGE }
    public enum RequestStatus { PENDING, APPROVED, REJECTED }
    public enum Decision { APPROVE, REJECT }

    public record AuthorityRequest(long id, String username, String organizationId, String systemId,
            RequestKind kind, List<String> authorityIds, LocalDate validFrom, LocalDate validTo, String reason,
            RequestStatus status, Instant requestedAt, String reviewer, Instant reviewedAt, String reviewReason,
            int workflowVersion, String ssoOfficeCode, String ssoDepartmentCode) {
        public AuthorityRequest {
            if (workflowVersion == 0) workflowVersion = 1;
            if (workflowVersion < 1 || workflowVersion > 3) throw new IllegalArgumentException("알 수 없는 권한 신청 버전입니다.");
            if (workflowVersion == 3 && (ssoOfficeCode == null || ssoOfficeCode.isBlank()
                    || ssoDepartmentCode == null || ssoDepartmentCode.isBlank())) {
                throw new IllegalArgumentException("SSO 권한 신청에는 확정된 소속이 필요합니다.");
            }
        }
        public AuthorityRequest(long id, String username, String organizationId, String systemId,
                RequestKind kind, List<String> authorityIds, LocalDate validFrom, LocalDate validTo, String reason,
                RequestStatus status, Instant requestedAt, String reviewer, Instant reviewedAt, String reviewReason, int workflowVersion) {
            this(id, username, organizationId, systemId, kind, authorityIds, validFrom, validTo, reason, status,
                    requestedAt, reviewer, reviewedAt, reviewReason, workflowVersion, null, null);
        }
        public AuthorityRequest(long id, String username, String organizationId, String systemId,
                RequestKind kind, List<String> authorityIds, LocalDate validFrom, LocalDate validTo, String reason,
                RequestStatus status, Instant requestedAt, String reviewer, Instant reviewedAt, String reviewReason) {
            this(id, username, organizationId, systemId, kind, authorityIds, validFrom, validTo, reason, status,
                    requestedAt, reviewer, reviewedAt, reviewReason, 1, null, null);
        }
    }

    @Override public String stateKey() { return "authority-requests"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() {
        return new StoredState(List.copyOf(requests.values()), Map.copyOf(baselines), sequence);
    }
    @Override public void restoreState(Object raw) {
        StoredState state = (StoredState) raw;
        requests.clear(); state.requests().forEach(item -> requests.put(item.id(), item));
        baselines.clear(); baselines.putAll(state.baselines()); sequence = state.sequence();
    }
    public record StoredState(List<AuthorityRequest> requests, Map<Long, List<AuthorityAssignment>> baselines, long sequence) { }
}
