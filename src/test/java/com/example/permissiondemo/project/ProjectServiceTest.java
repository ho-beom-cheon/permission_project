package com.example.permissiondemo.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 프로젝트 마스터·참여자 규칙과 낙관적 잠금을 DB 없이 검증한다. */
class ProjectServiceTest {

    private ProjectService service;
    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        auditEventService = new AuditEventService();
        service = new ProjectService(
                new InMemoryProjectRepository(),
                new AuthorizationCatalog(),
                auditEventService,
                clock);
    }

    @Test
    void projectCanBeCreatedWithOwnerMembership() {
        ProjectModels.Project saved = service.saveProject(new ProjectModels.Project(
                "prj_new_service",
                "신규 서비스 구축",
                "서비스 고도화",
                ProjectModels.ProjectStatus.PLANNED,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31),
                "admin",
                true,
                0));

        assertThat(saved.id()).isEqualTo("PRJ_NEW_SERVICE");
        assertThat(saved.version()).isEqualTo(1);
        assertThat(service.loadProjects().projects())
                .filteredOn(detail -> detail.project().id().equals("PRJ_NEW_SERVICE"))
                .singleElement()
                .satisfies(detail -> assertThat(detail.members())
                        .extracting(ProjectModels.ProjectMember::username)
                        .containsExactly("admin"));
        assertThat(service.statusHistory(saved.id()))
                .first()
                .satisfies(history -> {
                    assertThat(history.fromStatus()).isNull();
                    assertThat(history.toStatus()).isEqualTo(ProjectModels.ProjectStatus.PLANNED);
                    assertThat(history.changedAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
                });
    }

    @Test
    void demoSeedProvidesOperationalPortfolioAcrossStatusesRolesAndArchive() {
        var details = service.loadProjects().projects();

        assertThat(details).hasSize(12);
        assertThat(details)
                .extracting(detail -> detail.project().id())
                .contains("PRJ_ACCESS_RENEWAL", "PRJ_DATA_HUB", "PRJ_MOBILE_PORTAL");

        Map<ProjectModels.ProjectStatus, Long> statusCounts = new EnumMap<>(
                ProjectModels.ProjectStatus.class);
        details.forEach(detail -> statusCounts.merge(
                detail.project().status(), 1L, Long::sum));
        assertThat(statusCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                ProjectModels.ProjectStatus.PLANNED, 3L,
                ProjectModels.ProjectStatus.ACTIVE, 4L,
                ProjectModels.ProjectStatus.ON_HOLD, 2L,
                ProjectModels.ProjectStatus.COMPLETED, 3L));
        assertThat(details)
                .filteredOn(detail -> !detail.project().active())
                .extracting(detail -> detail.project().id())
                .containsExactlyInAnyOrder("PRJ_LEGACY_MIGRATION", "PRJ_DISASTER_RECOVERY");

        assertThat(details).allSatisfy(detail -> {
            assertThat(detail.members())
                    .filteredOn(ProjectModels.ProjectMember::active)
                    .extracting(ProjectModels.ProjectMember::role)
                    .contains(ProjectModels.ProjectRole.OWNER,
                            ProjectModels.ProjectRole.MANAGER,
                            ProjectModels.ProjectRole.MEMBER,
                            ProjectModels.ProjectRole.VIEWER);
            assertThat(detail.members())
                    .filteredOn(member -> member.active()
                            && member.role() == ProjectModels.ProjectRole.OWNER)
                    .extracting(ProjectModels.ProjectMember::username)
                    .containsExactly(detail.project().ownerUsername());
            assertThat(service.statusHistory(detail.project().id()))
                    .isNotEmpty()
                    .allSatisfy(history -> assertThat(history.reason()).isNotBlank())
                    .first()
                    .extracting(ProjectModels.ProjectStatusHistory::toStatus)
                    .isEqualTo(detail.project().status());
        });
    }

    @Test
    void staleProjectVersionIsRejected() {
        ProjectModels.Project current = service.loadProjects().projects().stream()
                .map(ProjectService.ProjectDetail::project)
                .filter(project -> project.id().equals("PRJ_ACCESS_RENEWAL"))
                .findFirst()
                .orElseThrow();
        service.saveProject(new ProjectModels.Project(
                current.id(), current.name(), current.description(), current.status(),
                current.startDate(), current.endDate(), current.ownerUsername(), current.active(),
                current.version()));

        assertThatThrownBy(() -> service.saveProject(current))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void projectOwnerCannotBeRemovedOrDemoted() {
        assertThatThrownBy(() -> service.saveMember(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "admin", ProjectModels.ProjectRole.MEMBER, true)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service.removeMember("PRJ_ACCESS_RENEWAL", "admin"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void projectsCanBeFilteredSortedAndPaged() {
        ProjectService.ProjectSearchView result = service.searchProjects(
                new ProjectModels.ProjectSearchCriteria(
                        "권한",
                        Set.of(ProjectModels.ProjectStatus.ACTIVE),
                        "admin",
                        true,
                        ProjectModels.ProjectSort.START_DATE,
                        ProjectModels.SortDirection.DESC,
                        0,
                        1));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.projects())
                .extracting(detail -> detail.project().id())
                .containsExactly("PRJ_ACCESS_RENEWAL");
    }

    @Test
    void ownerTransferKeepsExactlyOneOwnerAndDemotesPreviousOwner() {
        ProjectModels.Project saved = service.transferOwnership(
                "PRJ_ACCESS_RENEWAL", "manager", 1, "admin", "책임자 변경");

        assertThat(saved.ownerUsername()).isEqualTo("manager");
        assertThat(saved.version()).isEqualTo(2);
        assertThat(service.getProject(saved.id()).members())
                .filteredOn(ProjectModels.ProjectMember::active)
                .filteredOn(member -> member.role() == ProjectModels.ProjectRole.OWNER)
                .extracting(ProjectModels.ProjectMember::username)
                .containsExactly("manager");
        assertThat(service.getProject(saved.id()).members())
                .filteredOn(member -> member.username().equals("admin"))
                .extracting(ProjectModels.ProjectMember::role)
                .containsExactly(ProjectModels.ProjectRole.MANAGER);
        assertThat(auditEventService.findPage(PageQuery.of(0, 10)).content())
                .first()
                .extracting(AuditEventService.AuditEvent::eventType)
                .isEqualTo("PROJECT_OWNER_TRANSFERRED");
    }

    @Test
    void managerCannotTransferOwnershipAndNonOwnerCannotClaimOwnerRole() {
        assertThatThrownBy(() -> service.transferOwnership(
                "PRJ_ACCESS_RENEWAL", "viewer", 1, "manager", "권한 없는 이전"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        assertThatThrownBy(() -> service.saveMember(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "manager", ProjectModels.ProjectRole.OWNER, true)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void globalProjectManagerCanOperateWithoutProjectMembership() {
        assertThat(service.evaluateAccess("PRJ_ACCESS_RENEWAL", "projectManager").capabilities())
                .contains(ProjectModels.ProjectCapability.MANAGE_MEMBERS,
                        ProjectModels.ProjectCapability.TRANSFER_OWNERSHIP,
                        ProjectModels.ProjectCapability.ARCHIVE);

        ProjectModels.Project archived = service.archiveProject(
                "PRJ_ACCESS_RENEWAL", 1, "projectManager", "관리자 보관 처리");

        assertThat(archived.active()).isFalse();
    }

    @Test
    void roleCapabilitiesRespectProjectLifecycle() {
        assertThat(service.evaluateAccess("PRJ_ACCESS_RENEWAL", "admin").capabilities())
                .contains(ProjectModels.ProjectCapability.TRANSFER_OWNERSHIP,
                        ProjectModels.ProjectCapability.ARCHIVE,
                        ProjectModels.ProjectCapability.MANAGE_MEMBERS);
        assertThat(service.evaluateAccess("PRJ_ACCESS_RENEWAL", "manager").capabilities())
                .contains(ProjectModels.ProjectCapability.UPDATE,
                        ProjectModels.ProjectCapability.MANAGE_MEMBERS)
                .doesNotContain(ProjectModels.ProjectCapability.TRANSFER_OWNERSHIP);
        assertThat(service.evaluateAccess("PRJ_ACCESS_RENEWAL", "codeManager").capabilities())
                .isEmpty();

        ProjectModels.Project archived = service.archiveProject(
                "PRJ_ACCESS_RENEWAL", 1, "admin", "운영 종료");
        assertThat(archived.active()).isFalse();
        assertThat(service.evaluateAccess(archived.id(), "admin").capabilities())
                .containsExactlyInAnyOrder(
                        ProjectModels.ProjectCapability.VIEW,
                        ProjectModels.ProjectCapability.RESTORE);
        assertThat(service.restoreProject(
                archived.id(), archived.version(), "admin", "재개").active()).isTrue();
    }

    @Test
    void statusTransitionRequiresValidPathAndProducesReasonedHistory() {
        ProjectModels.Project current = service.getProject("PRJ_ACCESS_RENEWAL").project();
        ProjectModels.Project saved = service.saveProject(new ProjectModels.Project(
                        current.id(), current.name(), current.description(),
                        ProjectModels.ProjectStatus.ON_HOLD,
                        current.startDate(), current.endDate(), current.ownerUsername(),
                        current.active(), current.version()),
                "admin",
                "운영 정책 확정 대기");

        assertThat(service.statusHistory(saved.id()))
                .first()
                .satisfies(history -> {
                    assertThat(history.fromStatus()).isEqualTo(ProjectModels.ProjectStatus.ACTIVE);
                    assertThat(history.toStatus()).isEqualTo(ProjectModels.ProjectStatus.ON_HOLD);
                    assertThat(history.changedBy()).isEqualTo("admin");
                    assertThat(history.reason()).isEqualTo("운영 정책 확정 대기");
                });

        ProjectModels.Project completed = service.getProject("PRJ_AUDIT_AUTOMATION").project();
        assertThatThrownBy(() -> service.saveProject(new ProjectModels.Project(
                completed.id(), completed.name(), completed.description(),
                ProjectModels.ProjectStatus.ACTIVE,
                completed.startDate(), completed.endDate(), completed.ownerUsername(),
                completed.active(), completed.version())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void archivedOrCompletedProjectRejectsMemberMutation() {
        assertThatThrownBy(() -> service.saveMember(new ProjectModels.ProjectMember(
                "PRJ_LEGACY_MIGRATION", "viewer", ProjectModels.ProjectRole.VIEWER, true)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.removeMember("PRJ_AUDIT_AUTOMATION", "viewer"))
                .isInstanceOf(ApiException.class);
    }
}
