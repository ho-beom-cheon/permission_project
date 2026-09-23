package com.example.permissiondemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import com.example.permissiondemo.audit.AuditEventService;
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

/** 실제 Oracle에서 공통코드 로드·저장·계층 비활성화·버전을 롤백 검증한다. */
@EnabledIfEnvironmentVariable(named = "PM_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_PASSWORD", matches = ".+")
class CommonCodeMapperOracleIntegrationTest {

    private SqlSession session;
    private CommonCodeService service;
    private CommonCodeMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "oracle.jdbc.OracleDriver",
                System.getenv("PM_DB_URL"),
                System.getenv("PM_DB_USERNAME"),
                System.getenv("PM_DB_PASSWORD"));
        Configuration configuration = new Configuration(new Environment(
                "common-code-oracle-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Resources.getResourceAsStream("mappers/CommonCodeMapper.xml")) {
            new XMLMapperBuilder(
                    input, configuration, "mappers/CommonCodeMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
        mapper = session.getMapper(CommonCodeMapper.class);
        service = new CommonCodeService(
                new AuditEventService(),
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC),
                new OracleCommonCodePersistence(mapper));
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.rollback();
            session.close();
        }
    }

    @Test
    void commonCodeRoundTripKeepsCascadeAndVersion() {
        assertThat(service.groups())
                .extracting(CommonCodeService.CommonCodeGroup::groupCode)
                .contains("USE_YN", "PROJECT_STATUS");

        service.saveGroup("CODEX_TEST", "통합 검증", "롤백 대상", 900, true);
        service.saveItem("CODEX_TEST", "ROOT", "상위", null, 10, true, null, null);
        service.saveItem("CODEX_TEST", "CHILD", "하위", "ROOT", 20, true, null, null);
        service.deactivateItem("CODEX_TEST", "ROOT");

        assertThat(mapper.selectItems().stream()
                .filter(row -> row.groupCode().equals("CODEX_TEST")))
                .extracting(CommonCodeMapper.ItemRow::code, CommonCodeMapper.ItemRow::active)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ROOT", false),
                        org.assertj.core.groups.Tuple.tuple("CHILD", false));
        assertThat(mapper.selectGroups().stream()
                .filter(row -> row.groupCode().equals("CODEX_TEST"))
                .findFirst().orElseThrow().version()).isEqualTo(4);
    }
}
