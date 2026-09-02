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

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
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
    private final AuthorizationCatalog catalog;
    private final AuditEventService audit;
    private final Clock clock;
    private final Map<Long, AuthorityRequest> requests = new LinkedHashMap<>();
    private final Map<Long, List<AuthorityAssignment>> baselines = new HashMap<>();
    private long sequence;
    private final com.example.permissiondemo.common.InboxService inbox;

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
            validatePeriod(validFrom, validTo);
            String cleanReason = requiredReason(reason);
            List<String> ids = authorityIds.stream().sorted().toList();
            String systemId = requireActiveAuthority(ids.get(0)).systemId();
            for (String id : ids) {
                if (!requireActiveAuthority(id).systemId().equals(systemId)) {
                    throw new IllegalArgumentException("한 신청에는 같은 시스템의 권한만 선택할 수 있습니다.");
                }
            }
            boolean pending = requests.values().stream().anyMatch(item -> item.username().equals(username)
                    && item.organizationId().equals(user.organizationId()) && item.systemId().equals(systemId)
                    && item.status() == RequestStatus.PENDING);
            if (pending) {
                throw new ApiException(ErrorCode.CONFLICT, "이미 처리 대기 중인 신청이 있습니다.");
            }
            AuthorityRequest request = new AuthorityRequest(++sequence, username, user.organizationId(),
                    systemId, kind, ids, validFrom, validTo, cleanReason, RequestStatus.PENDING,
                    Instant.now(clock), null, null, null);
            requests.put(request.id(), request);
            baselines.put(request.id(), latestDirectAssignments(username, user.organizationId(), systemId));
            audit.record("AUTHORITY_REQUESTED", "AUTHORITY_REQUEST", String.valueOf(request.id()),
                    "SUCCESS", Map.of("kind", kind.name(), "count", ids.size()));
            return request;
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

    /**
     * 일괄 처리 전에 모든 신청을 검증한다. 신청 당시 권한이 바뀐 경우 승인으로 덮어쓰지 않는다.
     * 카탈로그의 동일 모니터를 사용해 직접 부여·회수와 승인 처리가 교차하지 않게 한다.
     */
    public List<AuthorityRequest> review(String reviewer, List<Long> requestIds, Decision decision, String reason) {
        synchronized (catalog) {
            if (decision == null || requestIds == null || requestIds.isEmpty() || requestIds.size() > 100
                    || requestIds.stream().anyMatch(Objects::isNull)
                    || requestIds.stream().distinct().count() != requestIds.size()) {
                throw new IllegalArgumentException("처리할 신청을 중복 없이 1~100개 선택해 주세요.");
            }
            String reviewReason = decision == Decision.REJECT ? requiredReason(reason) : optionalReason(reason);
            List<AuthorityRequest> selected = requestIds.stream().map(id -> {
                AuthorityRequest request = requests.get(id);
                if (request == null) {
                    throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id);
                }
                if (request.status() != RequestStatus.PENDING) {
                    throw new ApiException(ErrorCode.CONFLICT, id);
                }
                if (decision == Decision.APPROVE) {
                    validateApproval(request);
                }
                return request;
            }).toList();
            List<AuthorityRequest> result = new ArrayList<>();
            for (AuthorityRequest request : selected) {
                if (decision == Decision.APPROVE) {
                    if (request.kind() == RequestKind.CHANGE) {
                        latestDirectAssignments(request.username(), request.organizationId(), request.systemId())
                                .stream().filter(item -> item.status() == AssignmentStatus.APPROVED)
                                .forEach(item -> catalog.revokeAssignment(item.username(), item.authorityId(), AssignmentType.DIRECT));
                    }
                    for (String id : request.authorityIds()) {
                        catalog.saveAssignment(request.username(), id, AssignmentType.DIRECT,
                                request.validFrom(), request.validTo(), null);
                    }
                }
                RequestStatus status = decision == Decision.APPROVE ? RequestStatus.APPROVED : RequestStatus.REJECTED;
                AuthorityRequest reviewed = new AuthorityRequest(request.id(), request.username(), request.organizationId(),
                        request.systemId(), request.kind(), request.authorityIds(), request.validFrom(), request.validTo(),
                        request.reason(), status, request.requestedAt(), reviewer, Instant.now(clock), reviewReason);
                requests.put(request.id(), reviewed);
                baselines.remove(request.id());
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

    private void validateApproval(AuthorityRequest request) {
        var user = catalog.findUser(request.username()).orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, request.id()));
        if (!user.active() || !user.organizationId().equals(request.organizationId())
                || !latestDirectAssignments(user.username(), user.organizationId(), request.systemId())
                        .equals(baselines.get(request.id()))) {
            throw new ApiException(ErrorCode.CONFLICT, "신청 이후 사용자 조직 또는 권한이 변경됐습니다.");
        }
        validatePeriod(request.validFrom(), request.validTo());
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
            RequestStatus status, Instant requestedAt, String reviewer, Instant reviewedAt, String reviewReason) { }

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
