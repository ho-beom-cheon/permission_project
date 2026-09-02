package com.example.permissiondemo.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 업무 집합의 변경 상태를 하나의 JDBC 트랜잭션으로 저장한다. 원본 테이블에는 접근하지 않는다. */
@Repository
public class ComponentStateRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ComponentStateRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS APP_STATE (STATE_KEY VARCHAR(80) PRIMARY KEY, FORMAT_VERSION INTEGER NOT NULL, PAYLOAD CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS APP_REVISION (ID INTEGER PRIMARY KEY, REVISION BIGINT NOT NULL)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM APP_REVISION", Integer.class) == 0) {
            jdbc.update("INSERT INTO APP_REVISION(ID, REVISION) VALUES (1, 0)");
        }
    }

    public Map<String, String> load() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT STATE_KEY, FORMAT_VERSION, PAYLOAD FROM APP_STATE", row -> {
            if (row.getInt("FORMAT_VERSION") != 1) throw new IllegalStateException("지원하지 않는 저장 데이터 버전입니다.");
            result.put(row.getString("STATE_KEY"), row.getString("PAYLOAD"));
        });
        return result;
    }

    public long revision() {
        return jdbc.queryForObject("SELECT REVISION FROM APP_REVISION WHERE ID=1", Long.class);
    }

    /** 첨부파일 등 별도 JDBC 테이블의 변경도 업무 상태 저장과 같은 트랜잭션에 참여한다. */
    public Object inTransaction(java.util.function.Supplier<Object> action) {
        return transaction.execute(status -> action.get());
    }

    /** 예상 버전이 다르면 덮어쓰지 않고 전체 변경을 롤백한다. */
    public void commit(long expectedRevision, Map<String, String> changed) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.update("UPDATE APP_REVISION SET REVISION=REVISION+1 WHERE ID=1 AND REVISION=?", expectedRevision) != 1) {
                throw new IllegalStateException("저장소가 다른 실행에서 변경됐습니다. 프로그램을 다시 시작해 주세요.");
            }
            changed.forEach((key, payload) -> jdbc.update(
                    "MERGE INTO APP_STATE(STATE_KEY, FORMAT_VERSION, PAYLOAD) KEY(STATE_KEY) VALUES (?, 1, ?)", key, payload));
        });
    }
}
