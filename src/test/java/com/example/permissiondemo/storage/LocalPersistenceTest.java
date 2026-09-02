package com.example.permissiondemo.storage;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.content.ContentService;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.LocalAccountService;
import com.example.permissiondemo.directory.PersonalScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 별도 합성 파일 DB를 닫고 다시 열어 복원을 확인한다. 원본 DB 설정을 사용하지 않는다. */
class LocalPersistenceTest {
    private ConfigurableApplicationContext open(String directory) {
        return new SpringApplicationBuilder(PermissionDemoApplication.class,ProbeConfiguration.class)
                .run("--spring.profiles.active=demo","--server.port=0","--server.address=127.0.0.1",
                        "--app.storage.memory=false","--app.storage.directory="+directory,
                        "--logging.level.root=ERROR","--spring.main.banner-mode=off");
    }
    private void authenticate(){
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","unused",List.of()));
    }
    private void shutdown(ConfigurableApplicationContext context){
        SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();
        context.getBean(JdbcTemplate.class).execute("SHUTDOWN");context.close();
    }
    @Test void diskReopenRestoresAccountsCodesPostsFilesSchedulesAndAudit()throws Exception{
        String directory="target/persistence-tests/"+UUID.randomUUID();
        String fileId; long postId;
        byte[] bytes="synthetic attachment only".getBytes(StandardCharsets.UTF_8);
        var first=open(directory);
        try {
            authenticate();
            var codes=first.getBean(CommonCodeService.class);
            codes.saveGroup(new CommonCodeService.CodeGroup("REOPEN","복원 검증","합성 그룹",true),0);
            codes.saveItem("REOPEN","ITEM","복원 상세",1,true);
            first.getBean(DirectoryService.class).savePerson(new DirectoryService.Person("disk_user","합성 사용자","HQ","","","","","","",true,Set.of()));
            first.getBean(LocalAccountService.class).create("disk_user","SyntheticOnly123!");
            var content=first.getBean(ContentService.class);
            fileId=content.upload("synthetic.txt",bytes).id();
            postId=content.save(0,new ContentService.WritePost(ContentService.Board.NOTICE,"복원 공지","합성 본문",true,true,false,null,null,"",List.of(fileId),0)).id();
            first.getBean(PersonalScheduleService.class).save(0,new PersonalScheduleService.WriteSchedule("복원 일정","합성 내용","TEST",PersonalScheduleService.Status.OPEN,
                    java.time.LocalDateTime.parse("2026-09-15T09:00"),java.time.LocalDateTime.parse("2026-09-15T10:00"),0));
        } finally { shutdown(first); }
        assertThat(Files.size(Path.of(directory,"common-work.mv.db"))).isGreaterThan(0);
        var second=open(directory);
        try {
            authenticate();
            assertThat(second.getBean(CommonCodeService.class).findActiveItems("REOPEN")).extracting(CommonCodeService.CommonCodeItem::name).containsExactly("복원 상세");
            var account=second.getBean(LocalAccountService.class).loadUserByUsername("disk_user");
            assertThat(second.getBean(PasswordEncoder.class).matches("SyntheticOnly123!",account.getPassword())).isTrue();
            assertThat(second.getBean(ContentService.class).get(postId).title()).isEqualTo("복원 공지");
            assertThat(second.getBean(ContentService.class).download(fileId).data()).isEqualTo(bytes);
            assertThat(second.getBean(PersonalScheduleService.class).list(java.time.LocalDate.of(2026,9,1),java.time.LocalDate.of(2026,9,30))).extracting(PersonalScheduleService.Schedule::name).containsExactly("복원 일정");
            assertThat(second.getBean(ComponentStateRepository.class).load().get("audit")).contains("POST_SAVED");
        } finally { shutdown(second); }
    }
    @Test void failureAfterBlobAndDomainWritesRollsBackBoth() {
        var context=open("target/persistence-tests/"+UUID.randomUUID());
        try {
            authenticate(); var jdbc=context.getBean(JdbcTemplate.class);var states=context.getBean(ComponentStateRepository.class);
            long before=states.revision();Map<String,String> snapshot=states.load();
            assertThatThrownBy(()->context.getBean(FailureProbe.class).fail()).isInstanceOf(IllegalStateException.class).hasMessage("synthetic rollback trigger");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APP_ATTACHMENT",Integer.class)).isZero();
            assertThat(context.getBean(CommonCodeService.class).groupSummary()).doesNotContainKey("ROLLBACK");
            assertThat(states.revision()).isEqualTo(before);assertThat(states.load()).isEqualTo(snapshot);
        } finally { shutdown(context); }
    }
    @TestConfiguration static class ProbeConfiguration {
        @Bean FailureProbe failureProbe(CommonCodeService codes,ContentService content){return new FailureProbe(codes,content);}
    }
    @StateBoundary public static class FailureProbe {
        private final CommonCodeService codes;private final ContentService content;
        FailureProbe(CommonCodeService codes,ContentService content){this.codes=codes;this.content=content;}
        public void fail(){
            codes.saveGroup(new CommonCodeService.CodeGroup("ROLLBACK","합성","",true),0);
            content.upload("rollback.txt",new byte[]{1,2,3});
            throw new IllegalStateException("synthetic rollback trigger");
        }
    }
}
