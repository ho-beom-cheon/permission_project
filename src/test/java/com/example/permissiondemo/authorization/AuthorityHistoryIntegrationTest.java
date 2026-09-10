package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthorityHistoryService.HistoryStatus;
import com.example.permissiondemo.authorization.AuthorityRequestService.Decision;
import com.example.permissiondemo.authorization.AuthorityRequestService.RequestKind;
import com.example.permissiondemo.common.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** 새 동작만 이력화하고, 부여·회수 상태와 사용자별 조회 경계를 함께 확인한다. */
@SpringBootTest(classes = PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser("admin")
class AuthorityHistoryIntegrationTest {
    @Autowired private AuthorityHistoryService history;
    @Autowired private AuthorityRequestService requests;
    @Autowired private AdminService admin;
    @Autowired private AuthorityRevocationService revocations;
    @Autowired private AuthorizationCatalog catalog;
    @Autowired private EffectiveAuthorityService effective;
    @Autowired private Clock clock;
    @Autowired private MockMvc mvc;

    @Test
    void requestApprovalAndChangeKeepEachEventWithoutDuplicateApproval() {
        LocalDate today = LocalDate.now(clock);
        assertThat(history.list("viewer", null, PageQuery.of(0, 100)).content()).isEmpty();
        var request = requests.submit("viewer", RequestKind.GRANT,
                Set.of("AUTH_CONTENT_MANAGER", "AUTH_SYSTEM_ADMIN"), today, today.plusYears(1), "신규 업무");
        assertThat(history.list("viewer", null, PageQuery.of(0, 100)).content()).hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.status()).isEqualTo(HistoryStatus.PENDING);
                    assertThat(event.processedAt()).isNull();
                    assertThat(event.actor()).isEqualTo("viewer");
                    assertThat(event.requestId()).isEqualTo(request.id());
                });
        requests.review("admin", List.of(request.id()), Decision.APPROVE, "확인 완료");
        var approved = history.list("viewer", HistoryStatus.APPROVED, PageQuery.of(0, 100)).content();
        assertThat(approved).hasSize(2).allSatisfy(event -> {
            assertThat(event.assignmentSequence()).isNotNull();
            assertThat(event.actor()).isEqualTo("admin");
            assertThat(event.reason()).isEqualTo("확인 완료");
            assertThat(event.processedAt()).isEqualTo(event.registeredAt());
        });
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition("AUTH_REPLACEMENT", "변경 대상", true, "INFO", null, ""));
        var change = requests.submit("viewer", RequestKind.CHANGE, Set.of("AUTH_REPLACEMENT"),
                today, today.plusYears(1), "업무 변경");
        requests.review("admin", List.of(change.id()), Decision.APPROVE, "변경 확인");
        var events = history.list("viewer", null, PageQuery.of(0, 100)).content();
        assertThat(events).hasSize(9);
        assertThat(events.get(0).status()).isEqualTo(HistoryStatus.APPROVED);
        assertThat(events.get(0).requestId()).isEqualTo(change.id());
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i - 1).registeredAt()).isAfterOrEqualTo(events.get(i).registeredAt());
        }
        assertThat(events.stream().filter(e -> e.status() == HistoryStatus.REVOKED).toList())
                .hasSize(3).allSatisfy(event -> {
                    assertThat(event.reason()).isEqualTo("권한변경신청");
                    assertThat(event.requestId()).isEqualTo(change.id());
                    assertThat(event.assignmentType()).isEqualTo(AssignmentType.DIRECT);
                });
        assertThat(effective.findEffectiveAuthorityIds("viewer")).containsExactly("AUTH_REPLACEMENT");
    }

    @Test
    void directAndDelegatedAdministrationAndBulkRevokeRecordActorsAndReasons() {
        LocalDate today = LocalDate.now(clock);
        admin.assignAuthority("expired", "AUTH_VIEWER", AssignmentType.DIRECT, today, today.plusDays(5), null);
        admin.assignAuthority("expired", "AUTH_CONTENT_MANAGER", AssignmentType.DELEGATED,
                today, today.plusDays(5), "manager");
        admin.revokeAuthority("expired", "AUTH_VIEWER", AssignmentType.DIRECT, "회수 사유");
        revocations.revoke(new AuthorityRevocationService.Request(
                List.of(new AuthorityRevocationService.Target("expired", "HQ")), "INFO", true,
                catalog.authorityVersion(), "일괄 회수 확인"));
        var events = history.list("expired", null, PageQuery.of(0, 100)).content();
        // 기존 만료 위임 권한도 현재 일괄 회수 정책의 대상이며 과거 부여 이벤트는 만들지 않는다.
        assertThat(events).hasSize(5).allSatisfy(event -> {
            assertThat(event.actor()).isEqualTo("admin");
            assertThat(event.processedAt()).isNotNull();
            assertThat(event.requestId()).isNull();
            assertThat(event.assignmentSequence()).isNotNull();
        });
        assertThat(events.stream().filter(e -> e.status() == HistoryStatus.APPROVED).toList()).hasSize(2);
        assertThat(events.stream().filter(e -> "일괄 회수 확인".equals(e.reason())).toList())
                .hasSize(2).allSatisfy(event -> assertThat(event.status()).isEqualTo(HistoryStatus.REVOKED));
    }

    @Test
    void rejectionPreservesPendingEventAndRecordsRejectedAssignment() {
        LocalDate today = LocalDate.now(clock);
        var before = catalog.assignmentsFor("moved");
        var request = requests.submit("moved", RequestKind.GRANT, Set.of("AUTH_CONTENT_MANAGER"),
                today, today.plusDays(5), "권한 신청");
        requests.review("admin", List.of(request.id()), Decision.REJECT, "업무 확인 필요");
        var events = history.list("moved", null, PageQuery.of(0, 100)).content();
        assertThat(events).extracting(AuthorityHistoryService.HistoryEvent::status)
                .containsExactly(HistoryStatus.REJECTED, HistoryStatus.PENDING);
        assertThat(events.get(0).reason()).isEqualTo("업무 확인 필요");
        assertThat(events.get(0).assignmentSequence()).isNotNull();
        assertThat(catalog.assignmentsFor("moved")).hasSize(before.size() + 2).containsAll(before);
        assertThat(catalog.assignmentsFor("moved").get(before.size() + 1).status())
                .isEqualTo(AuthorizationCatalog.AssignmentStatus.REJECTED);
    }

    @Test
    void historyApiUsesAuthenticatedOwnerAndRequiresAdministrator() throws Exception {
        LocalDate today = LocalDate.now(clock);
        requests.submit("manager", RequestKind.GRANT, Set.of("AUTH_SYSTEM_ADMIN"),
                today, today.plusDays(5), "관리 업무 신청");
        mvc.perform(get("/api/me/authority-history").param("username", "viewer").with(user("manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].username").value("manager"))
                .andExpect(jsonPath("$.data.content[0].processedAt").doesNotExist());
        mvc.perform(get("/api/admin/authority-history").with(user("viewer"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/authority-history").param("username", "manager")
                .param("status", "PENDING").with(user("admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].username").value("manager"));
    }
}
