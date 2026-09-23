package com.example.permissiondemo.authorization.workflow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AdminService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.EffectiveAuthorityService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.SortDirection;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 권한 신청과 승인 결정을 실제 권한 이력 저장과 함께 조정한다. */
@Service
public class AuthorityRequestService {

    private final AuthorityRequestRepository repository;
    private final AuthorizationCatalog catalog;
    private final EffectiveAuthorityService effectiveAuthorityService;
    private final AdminService adminService;
    private final AuditEventService auditEventService;
    private final Clock clock;

    public AuthorityRequestService(
            AuthorityRequestRepository repository,
            AuthorizationCatalog catalog,
            EffectiveAuthorityService effectiveAuthorityService,
            AdminService adminService,
            AuditEventService auditEventService,
            Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.effectiveAuthorityService = effectiveAuthorityService;
        this.adminService = adminService;
        this.auditEventService = auditEventService;
        this.clock = clock;
    }

    /** 로그인 사용자가 자기 권한을 신청한다. 승인 전에는 실제 권한 결과에 포함하지 않는다. */
    public AuthorityRequestModels.AuthorityRequest submit(
            String requesterUsername,
            AuthorityRequestModels.SubmitCommand command) {
        AuthorizationCatalog.UserProfile requester = requireUser(requesterUsername);
        String authorityId = normalizeId(command.authorityId());
        AuthorizationCatalog.AuthorityDefinition authority = catalog.findAuthority(authorityId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId));
        if (!authority.active()) {
            throw new ApiException(ErrorCode.CONFLICT, authorityId);
        }
        AuthorizationCatalog.AssignmentType type = command.assignmentType();
        if (type == null) {
            throw new IllegalArgumentException("권한 부여 유형은 필수입니다.");
        }
        LocalDate validFrom = command.validFrom();
        LocalDate validTo = command.validTo();
        if (validFrom == null || validTo == null || validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("권한 신청 기간을 확인해 주세요.");
        }
        String delegatedBy = normalizeDelegator(type, requester, authorityId, command.delegatedBy());
        String reason = requiredReason(command.reason());
        LocalDateTime now = LocalDateTime.now(clock);
        AuthorityRequestModels.AuthorityRequest saved = repository.insert(
                new AuthorityRequestModels.AuthorityRequest(
                        repository.nextId(),
                        requester.username(),
                        requester.username(),
                        requester.organizationId(),
                        authorityId,
                        type,
                        AuthorityRequestModels.RequestStatus.REQUESTED,
                        validFrom,
                        validTo,
                        delegatedBy,
                        reason,
                        null,
                        1,
                        now,
                        null,
                        null));
        auditEventService.record(
                "AUTHORITY_REQUESTED", "AUTHORITY_REQUEST", Long.toString(saved.id()), "SUCCESS",
                java.util.Map.of("authorityId", authorityId, "assignmentType", type.name()));
        return saved;
    }

    /**
     * 승인 대기 신청을 승인하고 같은 트랜잭션에서 실제 권한 이력을 추가한다.
     * demo에서도 경쟁 결정이 권한 부여에 끼어들지 않도록 승인·반려·취소는 같은 잠금을 사용한다.
     */
    @Transactional
    public synchronized AuthorityRequestModels.AuthorityRequest approve(
            long requestId,
            long expectedVersion,
            String approverUsername,
            String reason) {
        AuthorityRequestModels.AuthorityRequest request = requirePending(requestId, expectedVersion);
        requireDifferentApprover(request, approverUsername);
        AuthorityRequestModels.AuthorityRequest decided = decide(
                request,
                AuthorityRequestModels.RequestStatus.APPROVED,
                approverUsername,
                optionalDecisionReason(reason));
        adminService.assignAuthority(
                request.targetUsername(),
                request.authorityId(),
                request.assignmentType(),
                request.validFrom(),
                request.validTo(),
                request.delegatedBy());
        AuthorityRequestModels.AuthorityRequest saved =
                repository.updateDecision(decided, expectedVersion);
        auditDecision(saved);
        return saved;
    }

    /** 승인 대기 신청을 사유와 함께 반려한다. */
    @Transactional
    public synchronized AuthorityRequestModels.AuthorityRequest reject(
            long requestId,
            long expectedVersion,
            String approverUsername,
            String reason) {
        AuthorityRequestModels.AuthorityRequest request = requirePending(requestId, expectedVersion);
        requireDifferentApprover(request, approverUsername);
        AuthorityRequestModels.AuthorityRequest saved = repository.updateDecision(
                decide(
                        request,
                        AuthorityRequestModels.RequestStatus.REJECTED,
                        approverUsername,
                        requiredReason(reason)),
                expectedVersion);
        auditDecision(saved);
        return saved;
    }

    /** 신청자가 아직 처리되지 않은 자기 신청을 취소한다. */
    @Transactional
    public synchronized AuthorityRequestModels.AuthorityRequest cancel(
            long requestId,
            long expectedVersion,
            String actorUsername,
            String reason) {
        AuthorityRequestModels.AuthorityRequest request = requirePending(requestId, expectedVersion);
        if (!request.requesterUsername().equals(actorUsername)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, Long.toString(requestId));
        }
        AuthorityRequestModels.AuthorityRequest saved = repository.updateDecision(
                decide(
                        request,
                        AuthorityRequestModels.RequestStatus.CANCELLED,
                        actorUsername,
                        optionalDecisionReason(reason)),
                expectedVersion);
        auditDecision(saved);
        return saved;
    }

    /** 관리자 큐를 상태·사용자·권한 조건으로 검색해 제한된 페이지로 반환한다. */
    public PageResult<AuthorityRequestModels.AuthorityRequest> findAll(
            AuthorityRequestModels.RequestStatus status,
            String username,
            String authorityId,
            PageQuery pageQuery) {
        return findAll(status, username, authorityId, null, null, pageQuery);
    }

    public PageResult<AuthorityRequestModels.AuthorityRequest> findAll(
            AuthorityRequestModels.RequestStatus status,
            String username,
            String authorityId,
            AuthorityRequestModels.RequestSort sort,
            SortDirection direction,
            PageQuery pageQuery) {
        return PageResult.of(sorted(
                filter(status, username, authorityId, repository.findAll()), sort, direction), pageQuery);
    }

    /** 현재 사용자의 신청 이력을 최신 순으로 반환한다. */
    public PageResult<AuthorityRequestModels.AuthorityRequest> findMine(
            String username,
            AuthorityRequestModels.RequestStatus status,
            PageQuery pageQuery) {
        return findMine(username, status, null, null, null, pageQuery);
    }

    public PageResult<AuthorityRequestModels.AuthorityRequest> findMine(
            String username,
            AuthorityRequestModels.RequestStatus status,
            String authorityId,
            AuthorityRequestModels.RequestSort sort,
            SortDirection direction,
            PageQuery pageQuery) {
        List<AuthorityRequestModels.AuthorityRequest> mine = repository.findAll().stream()
                .filter(request -> request.requesterUsername().equals(username))
                .filter(request -> status == null || request.status() == status)
                .filter(request -> authorityId == null || authorityId.isBlank()
                        || request.authorityId().contains(authorityId.trim().toUpperCase(Locale.ROOT)))
                .toList();
        return PageResult.of(sorted(mine, sort, direction), pageQuery);
    }

    private List<AuthorityRequestModels.AuthorityRequest> sorted(
            List<AuthorityRequestModels.AuthorityRequest> source,
            AuthorityRequestModels.RequestSort sort,
            SortDirection direction) {
        AuthorityRequestModels.RequestSort resolvedSort = sort == null
                ? AuthorityRequestModels.RequestSort.REQUESTED_AT : sort;
        Comparator<AuthorityRequestModels.AuthorityRequest> comparator = switch (resolvedSort) {
            case REQUESTED_AT -> Comparator.comparing(AuthorityRequestModels.AuthorityRequest::requestedAt)
                    .thenComparingLong(AuthorityRequestModels.AuthorityRequest::id);
            case USERNAME -> Comparator.comparing(AuthorityRequestModels.AuthorityRequest::targetUsername)
                    .thenComparingLong(AuthorityRequestModels.AuthorityRequest::id);
            case AUTHORITY -> Comparator.comparing(AuthorityRequestModels.AuthorityRequest::authorityId)
                    .thenComparingLong(AuthorityRequestModels.AuthorityRequest::id);
            case STATUS -> Comparator.comparing(item -> item.status().name());
            case VALID_FROM -> Comparator.comparing(AuthorityRequestModels.AuthorityRequest::validFrom)
                    .thenComparingLong(AuthorityRequestModels.AuthorityRequest::id);
        };
        if (direction == null) {
            direction = resolvedSort == AuthorityRequestModels.RequestSort.REQUESTED_AT
                    ? SortDirection.DESC : SortDirection.ASC;
        }
        return source.stream().sorted(direction == SortDirection.DESC
                ? comparator.reversed() : comparator).toList();
    }

    private List<AuthorityRequestModels.AuthorityRequest> filter(
            AuthorityRequestModels.RequestStatus status,
            String username,
            String authorityId,
            List<AuthorityRequestModels.AuthorityRequest> source) {
        String normalizedUser = optionalLower(username);
        String normalizedAuthority = optionalUpper(authorityId);
        return source.stream()
                .filter(request -> status == null || request.status() == status)
                .filter(request -> normalizedUser == null
                        || request.targetUsername().toLowerCase(Locale.ROOT).contains(normalizedUser)
                        || request.requesterUsername().toLowerCase(Locale.ROOT).contains(normalizedUser))
                .filter(request -> normalizedAuthority == null
                        || request.authorityId().contains(normalizedAuthority))
                .toList();
    }

    private AuthorityRequestModels.AuthorityRequest requirePending(long requestId, long expectedVersion) {
        AuthorityRequestModels.AuthorityRequest request = repository.findById(requestId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, Long.toString(requestId)));
        if (request.status() != AuthorityRequestModels.RequestStatus.REQUESTED
                || request.version() != expectedVersion) {
            throw new ApiException(ErrorCode.CONFLICT, Long.toString(requestId));
        }
        return request;
    }

    private void requireDifferentApprover(
            AuthorityRequestModels.AuthorityRequest request,
            String approverUsername) {
        requireUser(approverUsername);
        if (request.requesterUsername().equals(approverUsername)) {
            throw new ApiException(ErrorCode.CONFLICT, approverUsername);
        }
    }

    private AuthorityRequestModels.AuthorityRequest decide(
            AuthorityRequestModels.AuthorityRequest request,
            AuthorityRequestModels.RequestStatus status,
            String actor,
            String reason) {
        return new AuthorityRequestModels.AuthorityRequest(
                request.id(),
                request.requesterUsername(),
                request.targetUsername(),
                request.organizationId(),
                request.authorityId(),
                request.assignmentType(),
                status,
                request.validFrom(),
                request.validTo(),
                request.delegatedBy(),
                request.requestReason(),
                reason,
                request.version() + 1,
                request.requestedAt(),
                LocalDateTime.now(clock),
                actor);
    }

    private AuthorizationCatalog.UserProfile requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "user");
        }
        return catalog.findUser(username)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
    }

    private String normalizeDelegator(
            AuthorizationCatalog.AssignmentType type,
            AuthorizationCatalog.UserProfile requester,
            String authorityId,
            String delegatedBy) {
        if (type == AuthorizationCatalog.AssignmentType.DIRECT) {
            if (delegatedBy != null && !delegatedBy.isBlank()) {
                throw new IllegalArgumentException("직접 권한에는 위임 원천 사용자를 지정할 수 없습니다.");
            }
            return null;
        }
        if (delegatedBy == null || delegatedBy.isBlank()
                || delegatedBy.equals(requester.username())) {
            throw new IllegalArgumentException("위임 권한에는 다른 원천 사용자가 필요합니다.");
        }
        AuthorizationCatalog.UserProfile source = requireUser(delegatedBy);
        if (!source.organizationId().equals(requester.organizationId())
                || !effectiveAuthorityService.hasDirectAuthority(source.username(), authorityId)) {
            throw new ApiException(ErrorCode.CONFLICT, delegatedBy);
        }
        return source.username();
    }

    private void auditDecision(AuthorityRequestModels.AuthorityRequest request) {
        auditEventService.record(
                "AUTHORITY_REQUEST_" + request.status().name(),
                "AUTHORITY_REQUEST",
                Long.toString(request.id()),
                "SUCCESS",
                java.util.Map.of(
                        "authorityId", request.authorityId(),
                        "targetUsername", request.targetUsername(),
                        "version", request.version()));
    }

    private String normalizeId(String value) {
        String normalized = optionalUpper(value);
        if (normalized == null || !normalized.matches("[A-Z0-9_]{1,50}")) {
            throw new IllegalArgumentException("authorityId 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    private String requiredReason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 500) {
            throw new IllegalArgumentException("사유는 1~500자여야 합니다.");
        }
        return value.trim();
    }

    private String optionalDecisionReason(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.trim().length() > 500) {
            throw new IllegalArgumentException("처리 사유는 500자 이하여야 합니다.");
        }
        return value.trim();
    }

    private String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String optionalLower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
