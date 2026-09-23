package com.example.permissiondemo.authorization.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import javax.sql.DataSource;

import com.example.permissiondemo.audit.AuditEventMapper;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.audit.MyBatisAuditEventRepository;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestMapper;
import com.example.permissiondemo.authorization.workflow.AuthorityRequestModels;
import com.example.permissiondemo.authorization.workflow.MyBatisAuthorityRequestRepository;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.security.LocalAccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** 실제 Oracle에서 권한 카탈로그·신청 워크플로·감사 저장 경로를 롤백 검증한다. */
@EnabledIfEnvironmentVariable(named = "PM_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_PASSWORD", matches = ".+")
class ManagementPersistenceOracleIntegrationTest {

    private SqlSession session;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "oracle.jdbc.OracleDriver",
                System.getenv("PM_DB_URL"),
                System.getenv("PM_DB_USERNAME"),
                System.getenv("PM_DB_PASSWORD"));
        Configuration configuration = new Configuration(new Environment(
                "management-oracle-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        parse(configuration, "mappers/AuthorizationCatalogMapper.xml");
        parse(configuration, "mappers/AuthorityRequestMapper.xml");
        parse(configuration, "mappers/AuditEventMapper.xml");
        parse(configuration, "mappers/LocalAccountMapper.xml");
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.rollback();
            session.close();
        }
    }

    @Test
    void catalogWorkflowAndAuditRoundTrip() {
        MyBatisAuthorizationCatalogPersistence catalog =
                new MyBatisAuthorizationCatalogPersistence(
                        session.getMapper(AuthorizationCatalogMapper.class));
        assertThat(catalog.loadSnapshot().authorities()).isNotEmpty();

        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition(
                "AUTH_CODEX_TEST", "Oracle 통합 검증", true));
        catalog.saveProgram(new AuthorizationCatalog.ProgramDefinition(
                "CODEX_TEST", "Oracle 검증", "롤백 대상", true));
        catalog.saveMenu(new AuthorizationCatalog.MenuDefinition(
                "CODEX_TEST", null, "Oracle 검증", "#codex-test", 950,
                true, true, false, AuthorizationCatalog.MenuArea.ADMIN));
        AuthorizationCatalog.ProgramActionDefinition action =
                new AuthorizationCatalog.ProgramActionDefinition(
                        "CODEX_TEST", "CODEX_TEST", "CODEX_EXECUTE", "실행",
                        null, 10, true);
        catalog.saveAction(action);
        catalog.setMenuGrant("AUTH_CODEX_TEST", "CODEX_TEST", true);
        catalog.setActionGrant("AUTH_CODEX_TEST", action.key(), true);
        catalog.insertAssignment(new AuthorizationCatalog.AuthorityAssignment(
                "viewer", "HQ", "AUTH_CODEX_TEST", AuthorizationCatalog.AssignmentType.DIRECT,
                AuthorizationCatalog.AssignmentStatus.APPROVED,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), null, 1));

        assertThat(catalog.loadSnapshot().actionGrants().get("AUTH_CODEX_TEST"))
                .contains(action.key());
        assertThat(catalog.loadSnapshot().menus()).filteredOn(menu -> "CODEX_TEST".equals(menu.id()))
                .singleElement().extracting(AuthorizationCatalog.MenuDefinition::area)
                .isEqualTo(AuthorizationCatalog.MenuArea.ADMIN);
        catalog.saveProgram(new AuthorizationCatalog.ProgramDefinition(
                "CODEX_TEST", "Oracle 검증", "화면 연결 롤백 검증", true, AuthorizationCatalog.ProgramScreen.AUTHORITY_REQUEST));
        assertThat(catalog.loadSnapshot().programs()).filteredOn(program -> "CODEX_TEST".equals(program.id()))
                .singleElement().extracting(AuthorizationCatalog.ProgramDefinition::screen)
                .isEqualTo(AuthorizationCatalog.ProgramScreen.AUTHORITY_REQUEST);

        MyBatisAuthorityRequestRepository requests = new MyBatisAuthorityRequestRepository(
                session.getMapper(AuthorityRequestMapper.class));
        long requestId = requests.nextId();
        AuthorityRequestModels.AuthorityRequest request = requests.insert(
                new AuthorityRequestModels.AuthorityRequest(
                        requestId, "viewer", "viewer", "HQ", "AUTH_CODEX_TEST",
                        AuthorizationCatalog.AssignmentType.DIRECT,
                        AuthorityRequestModels.RequestStatus.REQUESTED,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), null,
                        "통합 검증", null, 1,
                        LocalDateTime.of(2026, 9, 1, 12, 0), null, null));
        AuthorityRequestModels.AuthorityRequest approved = requests.updateDecision(
                new AuthorityRequestModels.AuthorityRequest(
                        request.id(), request.requesterUsername(), request.targetUsername(),
                        request.organizationId(), request.authorityId(), request.assignmentType(),
                        AuthorityRequestModels.RequestStatus.APPROVED,
                        request.validFrom(), request.validTo(), request.delegatedBy(),
                        request.requestReason(), "검증 승인", 2, request.requestedAt(),
                        LocalDateTime.of(2026, 9, 1, 12, 5), "admin"),
                1);
        assertThat(approved.status()).isEqualTo(AuthorityRequestModels.RequestStatus.APPROVED);

        MyBatisAuditEventRepository audits = new MyBatisAuditEventRepository(
                session.getMapper(AuditEventMapper.class), new ObjectMapper());
        long auditId = audits.nextId();
        audits.append(new AuditEventService.AuditEvent(
                auditId, "ORACLE_INTEGRATION_TEST", "SYSTEM", null,
                "MANAGEMENT", null, "SUCCESS", null, null,
                Instant.parse("2035-09-01T00:00:00Z"), Map.of("requestId", requestId)));
        assertThat(audits.findPage(PageQuery.of(0, 100)).content())
                .extracting(AuditEventService.AuditEvent::id)
                .contains(auditId);

        LocalAccountMapper.LocalAccountRow admin = session.getMapper(LocalAccountMapper.class)
                .selectByUsername("admin").orElseThrow();
        assertThat(admin.enabled()).isTrue();
        assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .matches("admin123!", admin.passwordHash())).isTrue();
    }

    private void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
                    .parse();
        }
    }
}
