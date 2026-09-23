package com.example.permissiondemo.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectSaveRegressionTest {
    private ProjectService service;
    private AuditEventService audit;

    @BeforeEach
    void setup() {
        audit = new AuditEventService();
        service = new ProjectService(new InMemoryProjectRepository(), new AuthorizationCatalog(), audit,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void archivedProjectCannotBeEditedOrRestoredByGeneralSave() {
        var archived = service.archiveProject("PRJ_ACCESS_RENEWAL", 1, "admin", "보관");
        assertThatThrownBy(() -> service.saveProject(copy(archived, "수정", archived.status(), false), "admin", "변경"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveProject(copy(archived, "복원 우회", archived.status(), true), "admin", "변경"))
                .isInstanceOf(ApiException.class);
        assertThat(service.getProject(archived.id()).project()).isEqualTo(archived);
        assertThat(service.restoreProject(archived.id(), archived.version(), "admin", "정상 복원").active()).isTrue();
    }

    @Test
    void activeFlagCannotBypassArchiveAndCompletedProjectIsImmutable() {
        var current = service.getProject("PRJ_ACCESS_RENEWAL").project();
        assertThatThrownBy(() -> service.saveProject(copy(current, current.name(), current.status(), false)))
                .isInstanceOf(ApiException.class);
        var completed = service.saveProject(copy(current, current.name(), ProjectModels.ProjectStatus.COMPLETED, true));
        assertThatThrownBy(() -> service.saveProject(copy(completed, "완료 후 변경", completed.status(), true)))
                .isInstanceOf(ApiException.class);
        assertThat(service.getProject(completed.id()).project()).isEqualTo(completed);
    }

    @Test
    void invalidHistoryReasonDoesNotChangeProjectMembersHistoryOrAudit() {
        var before = service.getProject("PRJ_ACCESS_RENEWAL");
        var beforeAudit = audit.findPage(PageQuery.of(0, 100));
        for (String reason : new String[]{null, "", " ", "x".repeat(301)}) {
            assertThatThrownBy(() -> service.saveProject(
                    copy(before.project(), "변경되면 안 됨", ProjectModels.ProjectStatus.ON_HOLD, true), "admin", reason))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.getProject(before.project().id())).isEqualTo(before);
            assertThat(audit.findPage(PageQuery.of(0, 100))).isEqualTo(beforeAudit);
        }
    }

    @Test
    void generalSaveEnforcesProjectRoleAndOwnerTransferCapability() {
        var current = service.getProject("PRJ_ACCESS_RENEWAL").project();
        assertThatThrownBy(() -> service.saveProject(current, "viewer", "변경"))
                .isInstanceOf(ApiException.class);
        var ownerChange = new ProjectModels.Project(current.id(), current.name(), current.description(), current.status(),
                current.startDate(), current.endDate(), "manager", true, current.version());
        assertThatThrownBy(() -> service.saveProject(ownerChange, "manager", "오너 변경"))
                .isInstanceOf(ApiException.class);
        assertThat(service.getProject(current.id()).project()).isEqualTo(current);
    }

    private ProjectModels.Project copy(ProjectModels.Project project, String name, ProjectModels.ProjectStatus status, boolean active) {
        return new ProjectModels.Project(project.id(), name, project.description(), status, project.startDate(),
                project.endDate(), project.ownerUsername(), active, project.version());
    }
}
