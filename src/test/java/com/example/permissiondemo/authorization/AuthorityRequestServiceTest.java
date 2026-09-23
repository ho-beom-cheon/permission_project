package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestModels;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestService;
import com.example.permissiondemo.authorization.workflow.InMemoryAuthorityRequestRepository;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 권한 신청이 승인 전에는 효력이 없고 승인 결정과 실제 권한 이력이 함께 반영되는지 검증한다. */
class AuthorityRequestServiceTest {

    private AuthorityRequestService service;
    private EffectiveAuthorityService effectiveAuthorityService;

    @BeforeEach
    void setUp() {
        AuthorizationCatalog catalog = new AuthorizationCatalog();
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        effectiveAuthorityService = new EffectiveAuthorityService(catalog, clock);
        AuditEventService audit = new AuditEventService();
        AdminService adminService = new AdminService(catalog, effectiveAuthorityService, audit);
        service = new AuthorityRequestService(
                new InMemoryAuthorityRequestRepository(), catalog, effectiveAuthorityService,
                adminService, audit, clock);
    }

    @Test
    void approvedRequestCreatesEffectiveAuthority() {
        AuthorityRequestModels.AuthorityRequest request = service.submit(
                "viewer",
                new AuthorityRequestModels.SubmitCommand(
                        AuthorizationCatalog.AUTH_CONTENT_MANAGER,
                        AuthorizationCatalog.AssignmentType.DIRECT,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 12, 31),
                        null,
                        "콘텐츠 운영 업무 수행"));

        assertThat(request.status()).isEqualTo(AuthorityRequestModels.RequestStatus.REQUESTED);
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("viewer"))
                .doesNotContain(AuthorizationCatalog.AUTH_CONTENT_MANAGER);

        AuthorityRequestModels.AuthorityRequest approved =
                service.approve(request.id(), request.version(), "admin", "업무 필요성 확인");

        assertThat(approved.status()).isEqualTo(AuthorityRequestModels.RequestStatus.APPROVED);
        assertThat(approved.version()).isEqualTo(2);
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("viewer"))
                .contains(AuthorizationCatalog.AUTH_CONTENT_MANAGER);
    }

    @Test
    void requesterCannotApproveOwnRequest() {
        AuthorityRequestModels.AuthorityRequest request = service.submit(
                "admin",
                new AuthorityRequestModels.SubmitCommand(
                        AuthorizationCatalog.AUTH_VIEWER,
                        AuthorizationCatalog.AssignmentType.DIRECT,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 12, 31),
                        null,
                        "조회 검증 권한 필요"));

        assertThatThrownBy(() -> service.approve(
                request.id(), request.version(), "admin", "자기 승인"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectedRequestStaysOutOfEffectiveAuthoritiesAndCanBeFiltered() {
        AuthorityRequestModels.AuthorityRequest request = service.submit(
                "viewer",
                new AuthorityRequestModels.SubmitCommand(
                        AuthorizationCatalog.AUTH_CONTENT_MANAGER,
                        AuthorizationCatalog.AssignmentType.DIRECT,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 12, 31),
                        null,
                        "임시 업무 지원"));

        AuthorityRequestModels.AuthorityRequest rejected =
                service.reject(request.id(), request.version(), "admin", "승인 근거 부족");

        assertThat(rejected.status()).isEqualTo(AuthorityRequestModels.RequestStatus.REJECTED);
        assertThat(effectiveAuthorityService.findEffectiveAuthorityIds("viewer"))
                .doesNotContain(AuthorizationCatalog.AUTH_CONTENT_MANAGER);
        assertThat(service.findAll(
                        AuthorityRequestModels.RequestStatus.REJECTED,
                        "view",
                        "CONTENT",
                        PageQuery.of(0, 10)).content())
                .containsExactly(rejected);
        assertThatThrownBy(() -> service.reject(
                request.id(), request.version(), "admin", "중복 처리"))
                .isInstanceOf(ApiException.class);
    }
}
