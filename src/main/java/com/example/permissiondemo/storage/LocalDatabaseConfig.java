package com.example.permissiondemo.storage;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.zaxxer.hikari.HikariDataSource;

/** 원본 Oracle 연결 설정을 읽지 않는 전용 내장 DB. 네트워크 URL을 설정으로 받지 않는다. */
@Configuration
public class LocalDatabaseConfig {
    @Bean(destroyMethod = "close")
    DataSource dataSource(@Value("${app.storage.memory:false}") boolean memory,
            @Value("${app.storage.directory:data}") String directory) {
        // 설정된 저장 폴더는 이 프로그램 실행 폴더 안에 있어야 한다.
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path target = workspace.resolve(directory).normalize();
        if (!target.startsWith(workspace) || target.equals(workspace) || directory.contains(";")) {
            throw new IllegalArgumentException("저장 폴더는 현재 프로젝트 안의 하위 폴더여야 합니다.");
        }
        try {
            Path existing = target;
            while (!Files.exists(existing)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(workspace.toRealPath())) {
                throw new IllegalArgumentException("저장 폴더의 실제 경로가 프로젝트 밖을 가리킵니다.");
            }
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("저장 폴더의 실제 경로를 확인할 수 없습니다.", failure);
        }
        String location = memory ? "mem:" + UUID.randomUUID()
                : "file:" + target.resolve("common-work").toString().replace('\\', '/');
        HikariDataSource source = new HikariDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setJdbcUrl("jdbc:h2:" + location + ";DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=TRUE;WRITE_DELAY=0");
        source.setUsername("sa");
        source.setPassword("");
        source.setMaximumPoolSize(4);
        source.setMinimumIdle(1);
        return source;
    }
}
