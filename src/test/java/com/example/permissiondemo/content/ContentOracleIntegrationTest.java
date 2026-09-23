package com.example.permissiondemo.content;

import static org.assertj.core.api.Assertions.*;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.web.ApiException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 Oracle 연결에서 CLOB, 검색 이스케이프와 일괄 변경의 트랜잭션 롤백을 검증한다. */
@EnabledIfEnvironmentVariable(named = "PM_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PM_DB_PASSWORD", matches = ".+")
class ContentOracleIntegrationTest {
    private OracleContentRepository repository;
    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;
    private final List<Long> createdIds = new ArrayList<>();
    @BeforeEach
    void setup() throws Exception {
        DataSource source = new UnpooledDataSource("oracle.jdbc.OracleDriver", System.getenv("PM_DB_URL"),
                System.getenv("PM_DB_USERNAME"), System.getenv("PM_DB_PASSWORD"));
        Configuration config = new Configuration(new Environment("content-test", new SpringManagedTransactionFactory(), source));
        try (InputStream input = Resources.getResourceAsStream("mappers/ContentMapper.xml")) {
            new XMLMapperBuilder(input, config, "mappers/ContentMapper.xml", config.getSqlFragments()).parse();
        }
        var template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        repository = new OracleContentRepository(template.getMapper(ContentMapper.class));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc = new JdbcTemplate(source);
    }
    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> jdbc.update("DELETE FROM PM_CONTENT WHERE CONTENT_ID = ? AND AUTHOR = ?", id, "content-integration-test"));
    }
    @Test
    void persistedUnicodeBodyLiteralSearchAndStaleBatchRollback() {
        var first = create("회귀 검증 %_literal", "한글 본문\n" + "가".repeat(9000));
        var second = create("회귀 검증 다른 제목", "본문");
        assertThat(repository.findById(first.id()).orElseThrow().body()).isEqualTo(first.body());
        assertThat(repository.search("title", "%_literal", PageQuery.of(0, 20)).content())
                .extracting(ContentPost::id).contains(first.id()).doesNotContain(second.id());
        var changedFirst = withStatus(first, 2);
        var staleSecond = withStatus(second, 99);
        assertThatThrownBy(() -> transaction.execute(status -> {
            repository.updateAll(List.of(changedFirst, staleSecond)); return null;
        })).isInstanceOf(ApiException.class);
        // 새 연결에서 읽어 첫 번째 UPDATE까지 롤백된 것을 확인한다.
        assertThat(repository.findById(first.id()).orElseThrow()).isEqualTo(first);
        transaction.execute(status -> { repository.updateAll(List.of(changedFirst, withStatus(second, 2))); return null; });
        assertThat(repository.findById(first.id()).orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(repository.findById(second.id()).orElseThrow().version()).isEqualTo(2);
    }
    private ContentPost create(String title, String body) {
        long id = repository.nextId(); createdIds.add(id);
        var now = Instant.parse("2026-09-24T00:00:00Z");
        var post = new ContentPost(id, "업무", title, body, "DRAFT", "content-integration-test", now, now, 1);
        transaction.execute(status -> { repository.insert(post); return null; });
        return post;
    }
    private ContentPost withStatus(ContentPost post, long version) {
        return new ContentPost(post.id(), post.type(), post.title(), post.body(), "PUBLISHED", post.author(),
                post.createdAt(), post.updatedAt(), version);
    }
}
