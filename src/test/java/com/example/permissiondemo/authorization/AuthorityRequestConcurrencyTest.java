package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestModels;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestModels.AuthorityRequest;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestModels.RequestStatus;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestService;
import com.example.permissiondemo.authorization.workflow.InMemoryAuthorityRequestRepository;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** demo 저장소의 실제 상태와 감사 이력이 경합 및 입력 오류 뒤에도 일치하는지 검증한다. */
class AuthorityRequestConcurrencyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"APPROVED", "CANCELLED", "REJECTED"})
    void approvalInProgressCannotLeaveAGrantOnACancelledOrRejectedRequest(
            RequestStatus competingDecision) throws Exception {
        Gate grantGate = new Gate();
        Fixture fixture = new Fixture(new InMemoryAuthorityRequestRepository(), grantGate::pause);
        AuthorityRequest request = fixture.submit();
        RunningDecision approval = start(() -> fixture.decide(request, RequestStatus.APPROVED));
        RunningDecision competitor = null;
        try {
            grantGate.awaitEntry();
            competitor = start(() -> fixture.decide(request, competingDecision));
            awaitBlockedOrFinished(competitor.thread());
            grantGate.release();

            assertThat(approval.result().get(5, TimeUnit.SECONDS).status()).isEqualTo(RequestStatus.APPROVED);
            assertConflict(competitor);
            fixture.assertOutcome(request.id(), RequestStatus.APPROVED);
        } finally {
            grantGate.release();
            finish(approval, competitor);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"CANCELLED", "REJECTED"})
    void cancellationOrRejectionInProgressPreventsApprovalSideEffects(
            RequestStatus firstDecision) throws Exception {
        Gate pendingReadGate = new Gate();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        InMemoryAuthorityRequestRepository repository = new InMemoryAuthorityRequestRepository() {
            @Override
            public Optional<AuthorityRequest> findById(long requestId) {
                Optional<AuthorityRequest> snapshot = super.findById(requestId);
                if (firstRead.compareAndSet(true, false)) {
                    pendingReadGate.pause();
                }
                return snapshot;
            }
        };
        Fixture fixture = new Fixture(repository, () -> {});
        AuthorityRequest request = fixture.submit();
        RunningDecision decision = start(() -> fixture.decide(request, firstDecision));
        RunningDecision approval = null;
        try {
            pendingReadGate.awaitEntry();
            approval = start(() -> fixture.decide(request, RequestStatus.APPROVED));
            awaitBlockedOrFinished(approval.thread());
            pendingReadGate.release();

            assertThat(decision.result().get(5, TimeUnit.SECONDS).status()).isEqualTo(firstDecision);
            assertConflict(approval);
            fixture.assertOutcome(request.id(), firstDecision);
        } finally {
            pendingReadGate.release();
            finish(decision, approval);
        }
    }

    @Test
    void invalidApprovalReasonLeavesRequestGrantVersionAndAuditUnchanged() {
        Fixture fixture = new Fixture(new InMemoryAuthorityRequestRepository(), () -> {});
        AuthorityRequest request = fixture.submit();
        long authorityVersion = fixture.catalog.authorityVersion();
        var beforeAudit = fixture.audit.findPage(PageQuery.of(0, 100)).content();

        assertThatThrownBy(() -> fixture.service.approve(
                request.id(), request.version(), "admin", "x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(fixture.repository.findById(request.id())).contains(request);
        assertThat(fixture.effective.findEffectiveAuthorityIds("viewer"))
                .doesNotContain(AuthorizationCatalog.AUTH_CONTENT_MANAGER);
        assertThat(fixture.catalog.authorityVersion()).isEqualTo(authorityVersion);
        assertThat(fixture.audit.findPage(PageQuery.of(0, 100)).content()).isEqualTo(beforeAudit);
    }

    private static void assertConflict(RunningDecision decision) {
        assertThatThrownBy(() -> decision.result().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private static RunningDecision start(Callable<AuthorityRequest> action) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<AuthorityRequest> result = new FutureTask<>(() -> {
            started.countDown();
            return action.call();
        });
        Thread thread = new Thread(result, "authority-decision-test");
        thread.setDaemon(true);
        thread.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        return new RunningDecision(thread, result);
    }

    /** 경쟁 호출이 실제 잠금에 도달하거나 완료된 뒤 진행해 임의 sleep에 의존하지 않는다. */
    private static void awaitBlockedOrFinished(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED) {
            assertThat(System.nanoTime()).as("competing decision reached the service").isLessThan(deadline);
            Thread.yield();
        }
    }

    private static void finish(RunningDecision... decisions) throws InterruptedException {
        for (RunningDecision decision : decisions) {
            if (decision != null) {
                decision.thread().join(5_000);
                assertThat(decision.thread().isAlive()).isFalse();
            }
        }
    }

    private record RunningDecision(Thread thread, FutureTask<AuthorityRequest> result) {}

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void pause() {
            entered.countDown();
            try {
                assertThat(released.await(10, TimeUnit.SECONDS)).as("decision gate released").isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }

        void awaitEntry() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("decision gate entered").isTrue();
        }

        void release() {
            released.countDown();
        }
    }

    private static final class Fixture {
        final AuthorizationCatalog catalog = new AuthorizationCatalog();
        final EffectiveAuthorityService effective = new EffectiveAuthorityService(catalog, CLOCK);
        final AuditEventService audit = new AuditEventService();
        final InMemoryAuthorityRequestRepository repository;
        final AuthorityRequestService service;

        Fixture(InMemoryAuthorityRequestRepository repository, Runnable afterGrant) {
            this.repository = repository;
            AdminService admin = new AdminService(catalog, effective, audit) {
                @Override
                public AuthorizationCatalog.AuthorityAssignment assignAuthority(
                        String username, String authorityId, AuthorizationCatalog.AssignmentType type,
                        LocalDate from, LocalDate to, String delegatedBy) {
                    var assignment = super.assignAuthority(username, authorityId, type, from, to, delegatedBy);
                    afterGrant.run();
                    return assignment;
                }
            };
            service = new AuthorityRequestService(repository, catalog, effective, admin, audit, CLOCK);
        }

        AuthorityRequest submit() {
            return service.submit("viewer", new AuthorityRequestModels.SubmitCommand(
                    AuthorizationCatalog.AUTH_CONTENT_MANAGER, AuthorizationCatalog.AssignmentType.DIRECT,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), null, "운영 지원"));
        }

        AuthorityRequest decide(AuthorityRequest request, RequestStatus status) {
            return switch (status) {
                case APPROVED -> service.approve(request.id(), request.version(), "admin", "승인");
                case CANCELLED -> service.cancel(request.id(), request.version(), "viewer", "취소");
                case REJECTED -> service.reject(request.id(), request.version(), "admin", "반려");
                default -> throw new IllegalArgumentException("최종 결정이 필요합니다.");
            };
        }

        void assertOutcome(long requestId, RequestStatus expected) {
            AuthorityRequest saved = repository.findById(requestId).orElseThrow();
            assertThat(saved.status()).isEqualTo(expected);
            assertThat(saved.version()).isEqualTo(2);
            assertThat(effective.findEffectiveAuthorityIds("viewer")
                    .contains(AuthorizationCatalog.AUTH_CONTENT_MANAGER)).isEqualTo(expected == RequestStatus.APPROVED);
            var events = audit.findPage(PageQuery.of(0, 100)).content();
            if (expected == RequestStatus.APPROVED) {
                assertThat(events).extracting(AuditEventService.AuditEvent::eventType)
                        .containsExactlyInAnyOrder("AUTHORITY_REQUESTED", "AUTHORITY_ASSIGNED", "AUTHORITY_REQUEST_APPROVED");
            } else {
                assertThat(events).extracting(AuditEventService.AuditEvent::eventType)
                        .containsExactlyInAnyOrder("AUTHORITY_REQUESTED", "AUTHORITY_REQUEST_" + expected.name());
            }
        }
    }
}
