package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.stereotype.Service;

/** 원본 권한위임 상세의 별도 저장 계약과 위임자 본인 처리 규칙을 보관한다. */
@Service
@StateBoundary
public class AuthorityDelegationService implements StateParticipant {

    private static final LocalDate PERMANENT_END = LocalDate.of(9999, 12, 31);

    private final AuthorizationCatalog catalog;
    private final EffectiveAuthorityService effective;
    private final Clock clock;
    private final List<Delegation> delegations = new ArrayList<>();
    private long sequence;

    public AuthorityDelegationService(
            AuthorizationCatalog catalog,
            EffectiveAuthorityService effective,
            Clock clock) {
        this.catalog = catalog;
        this.effective = effective;
        this.clock = clock;
    }

    /** 위임자가 현재 직접 승인 상태로 보유한 활성 권한만 선택 후보로 반환한다. */
    public synchronized List<AuthorizationCatalog.AuthorityDefinition> candidateAuthorities(
            String delegatorUsername) {
        requireActiveUser(delegatorUsername);
        return catalog.authorities().stream()
                .filter(AuthorizationCatalog.AuthorityDefinition::active)
                .filter(authority -> effective.hasDirectAuthority(delegatorUsername, authority.id()))
                .sorted(Comparator.comparing(AuthorizationCatalog.AuthorityDefinition::id))
                .toList();
    }

    /** 위임자의 현재 직접 권한 한 건을 오늘부터 원본의 영구 종료일까지 수임자에게 위임한다. */
    public synchronized Delegation create(
            String delegatorUsername,
            String recipientUsername,
            String authorityId,
            String reason) {
        String resolvedReason = requireReason(reason);
        LocalDate today = LocalDate.now(clock);
        requireActiveUser(delegatorUsername);
        requireActiveUser(recipientUsername);
        if (delegatorUsername.equals(recipientUsername)) {
            throw new IllegalArgumentException("본인에게 권한을 위임할 수 없습니다.");
        }
        if (activeForDelegator(delegatorUsername, today).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "이미 활성화된 권한위임이 존재합니다.");
        }
        AuthorizationCatalog.AuthorityDefinition authority = catalog.findAuthority(authorityId)
                .filter(AuthorizationCatalog.AuthorityDefinition::active)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId));
        if (!effective.hasDirectAuthority(delegatorUsername, authority.id())) {
            throw new ApiException(ErrorCode.CONFLICT, "현재 직접 승인된 권한만 위임할 수 있습니다.");
        }
        if (effective.findEffectiveAuthorityIds(recipientUsername).contains(authority.id())
                || hasActiveDelegatedAuthority(recipientUsername, authority.id(), today)) {
            throw new ApiException(ErrorCode.CONFLICT, "수임자가 이미 보유한 권한입니다.");
        }
        Delegation saved = new Delegation(++sequence, delegatorUsername, authority.id(), recipientUsername,
                today, PERMANENT_END, resolvedReason, Instant.now(clock));
        delegations.add(saved);
        return saved;
    }

    /** 위임자 본인의 현재 활성 위임을 원본과 같이 물리 삭제한다. */
    public synchronized Delegation cancel(String delegatorUsername) {
        LocalDate today = LocalDate.now(clock);
        Delegation current = activeForDelegator(delegatorUsername, today)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, delegatorUsername));
        delegations.remove(current);
        return current;
    }

    /** 위임자의 현재 활성 위임 한 건을 조회한다. */
    public synchronized Optional<Delegation> active(String delegatorUsername) {
        return activeForDelegator(delegatorUsername, LocalDate.now(clock));
    }

    /** 수임자의 현재 유효한 별도 위임을 반환한다. 최종 인가 결합은 별도 서비스가 담당한다. */
    public synchronized List<Delegation> activeForRecipient(String recipientUsername) {
        LocalDate today = LocalDate.now(clock);
        return delegations.stream()
                .filter(item -> item.recipientUsername().equals(recipientUsername) && item.isEffectiveOn(today))
                .sorted(Comparator.comparingLong(Delegation::delegationSequence))
                .toList();
    }

    /** 저장·관리 조회용 전체 위임 원본을 순번대로 반환한다. */
    public synchronized List<Delegation> all() {
        return delegations.stream().sorted(Comparator.comparingLong(Delegation::delegationSequence)).toList();
    }

    private Optional<Delegation> activeForDelegator(String username, LocalDate date) {
        return delegations.stream()
                .filter(item -> item.delegatorUsername().equals(username) && item.isEffectiveOn(date))
                .max(Comparator.comparingLong(Delegation::delegationSequence));
    }

    private boolean hasActiveDelegatedAuthority(String username, String authorityId, LocalDate date) {
        return delegations.stream().anyMatch(item -> item.recipientUsername().equals(username)
                && item.authorityId().equals(authorityId) && item.isEffectiveOn(date));
    }

    private void requireActiveUser(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("사용자 ID가 필요합니다.");
        }
        catalog.findUser(username).filter(AuthorizationCatalog.UserProfile::active)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("위임 사유는 1~1000자입니다.");
        }
        return reason.trim();
    }

    public record Delegation(
            long delegationSequence,
            String delegatorUsername,
            String authorityId,
            String recipientUsername,
            LocalDate validFrom,
            LocalDate validTo,
            String reason,
            Instant registeredAt) {
        public boolean isEffectiveOn(LocalDate date) {
            return !date.isBefore(validFrom) && !date.isAfter(validTo);
        }
    }

    public record StoredState(List<Delegation> delegations, long sequence) { }

    @Override public String stateKey() { return "authority-delegations"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public synchronized Object snapshotState() {
        return new StoredState(List.copyOf(delegations), sequence);
    }
    @Override public synchronized void restoreState(Object raw) {
        delegations.clear();
        if (raw == null) {
            sequence = 0;
            return;
        }
        StoredState state = (StoredState) raw;
        if (state.delegations() != null) delegations.addAll(state.delegations());
        long maximum = delegations.stream().mapToLong(Delegation::delegationSequence).max().orElse(0L);
        sequence = Math.max(state.sequence(), maximum);
    }
}
