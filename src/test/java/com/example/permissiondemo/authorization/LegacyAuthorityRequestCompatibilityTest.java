package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityAssignment;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** workflowVersion이 없던 실제 저장 JSON 형태를 복원하여 이전 심사 계약을 확인한다. */
class LegacyAuthorityRequestCompatibilityTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private AuthorityRequestService restoreLegacy(AuthorizationCatalog catalog) throws Exception {
        var payload = json.readTree("""
                {"requests":[{"id":1,"username":"viewer","organizationId":"HQ","systemId":"INFO",
                "kind":"CHANGE","authorityIds":["AUTH_VIEWER","AUTH_CONTENT_MANAGER"],
                "validFrom":"2026-09-01","validTo":"2027-12-31","reason":"이전 버전 변경 신청",
                "status":"PENDING","requestedAt":"2026-09-02T00:00:00Z",
                "reviewer":null,"reviewedAt":null,"reviewReason":null}],"baselines":{},"sequence":1}
                """);
        List<AuthorityAssignment> baseline = catalog.assignmentsFor("viewer").stream()
                .filter(a -> a.type() == AssignmentType.DIRECT && a.organizationId().equals("HQ"))
                .sorted(Comparator.comparing(AuthorityAssignment::authorityId)).toList();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("baselines")).set("1", json.valueToTree(baseline));
        var service = new AuthorityRequestService(catalog, new AuditEventService(), clock);
        service.restoreState(json.treeToValue(payload, AuthorityRequestService.StoredState.class));
        return service;
    }

    @Test
    void missingVersionRetainsMultipleChangeTargetsAndRequestedPeriod() throws Exception {
        var catalog = new AuthorizationCatalog();
        var service = restoreLegacy(catalog);
        assertThat(service.list("viewer", null, PageQuery.of(0, 20)).content().get(0).workflowVersion()).isEqualTo(1);
        var reviewed = service.review("admin", List.of(1L), AuthorityRequestService.Decision.APPROVE, "기존 신청 확인").get(0);
        assertThat(reviewed.workflowVersion()).isEqualTo(1);
        assertThat(reviewed.authorityIds()).containsExactly("AUTH_VIEWER", "AUTH_CONTENT_MANAGER");
        assertThat(reviewed.validFrom()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(reviewed.validTo()).isEqualTo(LocalDate.parse("2027-12-31"));
        assertThat(new EffectiveAuthorityService(catalog, clock).findEffectiveAuthorityIds("viewer"))
                .containsExactlyInAnyOrder("AUTH_VIEWER", "AUTH_CONTENT_MANAGER");
    }

    @Test
    void legacyPendingBlocksNewWorkflowAndKeepsWholeSystemBaseline() throws Exception {
        var catalog = new AuthorizationCatalog();
        var service = restoreLegacy(catalog);
        assertThatThrownBy(() -> service.submit("viewer", AuthorityRequestService.RequestKind.GRANT,
                Set.of("AUTH_SYSTEM_ADMIN"), null, null, "새 신청"))
                .isInstanceOf(ApiException.class);
        catalog.saveAssignment("viewer", "AUTH_SYSTEM_ADMIN", AssignmentType.DIRECT,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2027-12-31"), null);
        assertThatThrownBy(() -> service.review("admin", List.of(1L), AuthorityRequestService.Decision.APPROVE, "확인"))
                .isInstanceOf(ApiException.class);
        assertThat(service.list("viewer", null, PageQuery.of(0, 20)).content().get(0).status())
                .isEqualTo(AuthorityRequestService.RequestStatus.PENDING);
    }
}
