package com.example.permissiondemo.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import javax.sql.DataSource;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.web.ApiException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 환경변수가 제공될 때 실제 Oracle SQL과 Instant 매핑을 롤백 트랜잭션으로 검증한다. */
@EnabledIfEnvironmentVariable(named = "PM_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_PASSWORD", matches = ".+")
class ProjectMapperOracleIntegrationTest {

    private SqlSession sqlSession;
    private MyBatisProjectRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "oracle.jdbc.OracleDriver",
                System.getenv("PM_DB_URL"),
                System.getenv("PM_DB_USERNAME"),
                System.getenv("PM_DB_PASSWORD"));
        Environment environment = new Environment(
                "project-oracle-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Resources.getResourceAsStream("mappers/ProjectMapper.xml")) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mappers/ProjectMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(false);
        repository = new MyBatisProjectRepository(sqlSession.getMapper(ProjectMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.rollback();
            sqlSession.close();
        }
    }

    @Test
    void oracleSearchAndStatusHistoryRoundTrip() {
        ProjectModels.ProjectSearchCriteria criteria = new ProjectModels.ProjectSearchCriteria(
                "통합",
                Set.of(),
                "",
                true,
                ProjectModels.ProjectSort.ID,
                ProjectModels.SortDirection.ASC,
                0,
                20);

        ProjectModels.ProjectPage page = repository.search(criteria);

        assertThat(page.totalElements()).isPositive();
        assertThat(page.projects()).isNotEmpty();

        ProjectModels.Project project = page.projects().get(0);
        Instant changedAt = Instant.parse("2026-09-01T12:34:56.123456Z");
        ProjectModels.ProjectStatusHistory saved = repository.appendStatusHistory(
                new ProjectModels.ProjectStatusHistory(
                        project.id(),
                        0,
                        project.status(),
                        project.status(),
                        "SYSTEM",
                        "Oracle Mapper 통합 검증",
                        changedAt));

        assertThat(repository.findStatusHistory(project.id()))
                .filteredOn(history -> history.sequence() == saved.sequence())
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.reason()).isEqualTo("Oracle Mapper 통합 검증");
                    assertThat(history.changedAt()).isEqualTo(changedAt);
                });
    }

    @Test
    void oracleProjectSaveRejectsInvalidReasonAndArchiveBypassBeforeMutation() {
        ProjectService service = new ProjectService(repository, new AuthorizationCatalog(), new AuditEventService(),
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
        var created = service.saveProject(new ProjectModels.Project(
                "PRJ_REMEDIATION_TEST", "Oracle 저장 검증", "롤백되는 검증 데이터", ProjectModels.ProjectStatus.PLANNED,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), "admin", true, 0), "admin", null);
        var before = service.getProject(created.id());
        var invalidUpdate = new ProjectModels.Project(created.id(), "저장되면 안 됨", created.description(),
                ProjectModels.ProjectStatus.ACTIVE, created.startDate(), created.endDate(), "manager", true, created.version());
        for (String reason : new String[]{null, "", " ", "x".repeat(301)}) {
            assertThatThrownBy(() -> service.saveProject(invalidUpdate, "admin", reason))
                    .isInstanceOf(IllegalArgumentException.class);
            sqlSession.clearCache();
            assertThat(service.getProject(created.id())).isEqualTo(before);
        }
        var archived = service.archiveProject(created.id(), created.version(), "admin", "보관 검증");
        var restoreBypass = new ProjectModels.Project(archived.id(), "복원 우회", archived.description(),
                archived.status(), archived.startDate(), archived.endDate(), archived.ownerUsername(), true, archived.version());
        assertThatThrownBy(() -> service.saveProject(restoreBypass, "admin", "우회"))
                .isInstanceOf(ApiException.class);
        sqlSession.clearCache();
        assertThat(service.getProject(created.id()).project()).isEqualTo(archived);
        assertThat(service.restoreProject(created.id(), archived.version(), "admin", "전용 복원").active()).isTrue();
    }

    @Test
    void oracleSeedProvidesOperationalPortfolioAcrossStatusesRolesAndHistory() {
        Set<String> seededProjectIds = Set.of(
                "PRJ_ACCESS_RENEWAL",
                "PRJ_DATA_HUB",
                "PRJ_MOBILE_PORTAL",
                "PRJ_AUDIT_AUTOMATION",
                "PRJ_CUSTOMER_INSIGHT",
                "PRJ_LEGACY_MIGRATION",
                "PRJ_ZERO_TRUST",
                "PRJ_PROGRAM_CATALOG",
                "PRJ_PROJECT_PORTFOLIO",
                "PRJ_CODE_GOVERNANCE",
                "PRJ_REGIONAL_ROLLOUT",
                "PRJ_DISASTER_RECOVERY");

        var projects = repository.findAll();

        assertThat(projects).hasSizeGreaterThanOrEqualTo(12);
        assertThat(projects)
                .extracting(ProjectModels.Project::id)
                .containsAll(seededProjectIds);
        assertThat(projects)
                .filteredOn(project -> project.status() == ProjectModels.ProjectStatus.PLANNED)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(projects)
                .filteredOn(project -> project.status() == ProjectModels.ProjectStatus.ACTIVE)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(projects)
                .filteredOn(project -> project.status() == ProjectModels.ProjectStatus.ON_HOLD)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(projects)
                .filteredOn(project -> project.status() == ProjectModels.ProjectStatus.COMPLETED)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(projects)
                .filteredOn(project -> !project.active())
                .hasSizeGreaterThanOrEqualTo(2);

        seededProjectIds.forEach(projectId -> {
            ProjectModels.Project project = repository.findById(projectId).orElseThrow();
            assertThat(repository.findMembers(projectId))
                    .filteredOn(ProjectModels.ProjectMember::active)
                    .extracting(ProjectModels.ProjectMember::role)
                    .contains(ProjectModels.ProjectRole.OWNER,
                            ProjectModels.ProjectRole.MANAGER,
                            ProjectModels.ProjectRole.MEMBER,
                            ProjectModels.ProjectRole.VIEWER);
            assertThat(repository.findMembers(projectId))
                    .filteredOn(member -> member.active()
                            && member.role() == ProjectModels.ProjectRole.OWNER)
                    .extracting(ProjectModels.ProjectMember::username)
                    .containsExactly(project.ownerUsername());
            assertThat(repository.findStatusHistory(projectId))
                    .isNotEmpty()
                    .allSatisfy(history -> assertThat(history.reason()).isNotBlank())
                    .first()
                    .extracting(ProjectModels.ProjectStatusHistory::toStatus)
                    .isEqualTo(project.status());
        });
    }
}
