package com.example.permissiondemo.content;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 첨부 원문은 별도 BLOB 테이블에 저장한다. 경로·확장자에 따라 파일을 실행하지 않는다. */
@Repository
public class AttachmentRepository {
    private final JdbcTemplate jdbc;
    public AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS APP_ATTACHMENT (ID VARCHAR(36) PRIMARY KEY, OWNER_ID VARCHAR(50) NOT NULL, FILE_NAME VARCHAR(200) NOT NULL, FILE_SIZE BIGINT NOT NULL, CREATED_AT VARCHAR(40) NOT NULL, CONTENT BLOB NOT NULL)");
    }
    public void save(Metadata metadata, byte[] content) {
        jdbc.update("INSERT INTO APP_ATTACHMENT(ID,OWNER_ID,FILE_NAME,FILE_SIZE,CREATED_AT,CONTENT) VALUES (?,?,?,?,?,?)",
                metadata.id(), metadata.owner(), metadata.name(), metadata.size(), metadata.createdAt().toString(), content);
    }
    public Optional<Metadata> metadata(String id) {
        return jdbc.query("SELECT ID,OWNER_ID,FILE_NAME,FILE_SIZE,CREATED_AT FROM APP_ATTACHMENT WHERE ID=?",
                (row, index) -> new Metadata(row.getString(1), row.getString(2), row.getString(3), row.getLong(4), Instant.parse(row.getString(5))), id).stream().findFirst();
    }
    public byte[] content(String id) { return jdbc.queryForObject("SELECT CONTENT FROM APP_ATTACHMENT WHERE ID=?", byte[].class, id); }
    public void delete(String id) { jdbc.update("DELETE FROM APP_ATTACHMENT WHERE ID=?", id); }
    public record Metadata(String id, String owner, String name, long size, Instant createdAt) { }
}
