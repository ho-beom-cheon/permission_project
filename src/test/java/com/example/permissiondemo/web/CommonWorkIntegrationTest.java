package com.example.permissiondemo.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.example.permissiondemo.PermissionDemoApplication;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.storage.ComponentStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 전부 합성 입력을 사용한다. 실제 INFO DB나 원본 첨부파일을 사용하지 않는다. */
@SpringBootTest(classes=PermissionDemoApplication.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CommonWorkIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthorizationCatalog catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired ComponentStateRepository repository;

    private JsonNode send(MockHttpServletRequestBuilder request,String actor,Object body,int expected)throws Exception{
        var response=mvc.perform(request.with(user(actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }
    private Map<String,Object> person(String id,boolean active){return new LinkedHashMap<>(Map.of("username",id,"name","가상 사용자","officeCode","HQ","active",active));}
    @Test void storedPostsFromBeforeBoardConfigurationRemainReadable()throws Exception{
        long id=send(post("/api/posts"),"admin",postInput("NOTICE",true,true,List.of()),200).path("id").asLong();
        var state=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(repository.load().get("content"));state.remove("views");
        for(JsonNode post:state.path("posts"))((com.fasterxml.jackson.databind.node.ObjectNode)post).remove("boardId");
        var restored=json.treeToValue(state,com.example.permissiondemo.content.ContentService.StoredState.class);
        assertThat(restored.posts().stream().filter(p->p.id()==id).findFirst().orElseThrow().boardId()).isEqualTo("NOTICE");
        assertThat(restored.views()).isNull();
    }
    @Test void boardConfigurationControlsPostsAttachmentsAnswersAndVisibility()throws Exception{
        Map<String,Object> board=new LinkedHashMap<>(Map.of("id","TEST_QNA_BOARD","type","QNA","name","합성 질문 게시판","systemId","TEST","active",true,
                "noticeEnabled",false,"answerEnabled",false,"maxAttachments",0,"version",0));
        send(post("/api/boards"),"viewer",board,403);send(post("/api/boards"),"admin",board,200);
        Map<String,Object> input=postInput("QNA",true,false,List.of());input.put("boardId","TEST_QNA_BOARD");
        JsonNode created=send(post("/api/posts"),"viewer",input,200);long id=created.path("id").asLong();
        assertThat(created.path("boardId").asText()).isEqualTo("TEST_QNA_BOARD");
        mvc.perform(get("/api/boards/QNA").with(user("viewer"))).andExpect(jsonPath("$.data.content[?(@.id == "+id+")]").isEmpty());
        mvc.perform(get("/api/boards/TEST_QNA_BOARD").with(user("viewer"))).andExpect(jsonPath("$.data.totalElements").value(1));
        send(post("/api/posts/"+id+"/views"),"expired",Map.of(),403);
        assertThat(send(post("/api/posts/"+id+"/views"),"viewer",Map.of(),200).asLong()).isEqualTo(1);
        mvc.perform(get("/api/posts/"+id).with(user("viewer"))).andExpect(jsonPath("$.data.version").value(1));
        send(post("/api/posts/"+id+"/answer"),"admin",Map.of("answer","허용 전 답변","version",1),409);
        input.put("boardId","QNA");input.put("expectedVersion",1);send(put("/api/posts/"+id),"viewer",input,409);
        input.put("boardId","TEST_QNA_BOARD");input.put("expectedVersion",0);input.put("pinned",true);send(post("/api/posts"),"admin",input,409);input.put("pinned",false);
        var uploaded=mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file","board-test.txt","text/plain",new byte[]{1})).with(user("admin")).with(csrf())).andExpect(status().isOk()).andReturn();
        String fileId=json.readTree(uploaded.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        input.put("attachmentIds",List.of(fileId));send(post("/api/posts"),"admin",input,400);
        board.put("version",1);board.put("answerEnabled",true);send(post("/api/boards"),"admin",board,200);
        send(post("/api/posts/"+id+"/answer"),"admin",Map.of("answer","설정 후 합성 답변","version",1),200);
        send(delete("/api/boards/TEST_QNA_BOARD?version=1"),"admin",Map.of(),409);
        send(delete("/api/boards/TEST_QNA_BOARD?version=2"),"admin",Map.of(),200);
        mvc.perform(get("/api/boards").with(user("viewer"))).andExpect(jsonPath("$.data[?(@.id == 'TEST_QNA_BOARD')]").isEmpty());
        mvc.perform(get("/api/posts/"+id).with(user("viewer"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/posts/"+id).with(user("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("설정 후 합성 답변"));
        input.put("attachmentIds",List.of());send(post("/api/posts"),"admin",input,409);
        board.put("id","TEST_NOTICE_FEED");board.put("type","NOTICE");board.put("version",0);board.put("answerEnabled",false);
        send(post("/api/boards"),"admin",board,200);
        input=postInput("NOTICE",true,true,List.of());input.put("boardId","TEST_NOTICE_FEED");long noticeId=send(post("/api/posts"),"admin",input,200).path("id").asLong();
        mvc.perform(get("/api/notice-feed").with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[?(@.id == "+noticeId+")].boardId").value("TEST_NOTICE_FEED"));
        send(delete("/api/boards/TEST_NOTICE_FEED?version=1"),"admin",Map.of(),200);
        mvc.perform(get("/api/notice-feed").with(user("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.content[?(@.id == "+noticeId+")]").isEmpty());
    }
    @Test void printTextsUseGroupedKeysOrderedLinesAndAtomicValidation()throws Exception{
        String base="/api/admin/print-texts";
        mvc.perform(get(base).with(user("viewer"))).andExpect(status().isForbidden());
        for(String group:List.of("CM013","CM040"))send(post("/api/admin/codes"),"admin",Map.of("group",Map.of("code",group,"name","합성 출력 분류","active",true),"version",0),200);
        send(post("/api/admin/codes/CM013"),"admin",Map.of("item",Map.of("code","TT","name","합성 업무","active",true),"version",1),200);
        send(post("/api/admin/codes/CM040"),"admin",Map.of("item",Map.of("code","TT01","name","합성 상세","active",true),"version",1),200);
        send(post("/api/admin/codes/CM040"),"admin",Map.of("item",Map.of("code","XX01","name","다른 업무 상세","active",true),"version",2),200);
        Map<String,Object> value=new LinkedHashMap<>(Map.of("officeCd","HQ","jobSeCd","TT","jobSeDetailCd","XX01","version",0,
                "lines",List.of(Map.of("seq",3,"cn",""),Map.of("seq",1,"cn","합성 안내문구"))));
        send(post(base),"admin",value,409); value.put("jobSeDetailCd","TT01");
        JsonNode created=send(post(base),"admin",value,200);String id=created.path("id").asText();
        assertThat(created.path("contentCount").asLong()).isEqualTo(1);
        assertThat(created.path("lines").get(0).path("seq").asInt()).isEqualTo(1);
        value.put("version",1);value.put("lines",List.of(Map.of("seq",1,"cn","첫 행 변경"),Map.of("seq",1,"cn","중복 순번")));
        send(post(base),"admin",value,400);
        mvc.perform(get(base+"/{id}",id).with(user("admin"))).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.lines[0].cn").value("합성 안내문구"));
        value.put("lines",List.of(Map.of("seq",1,"cn","가".repeat(101))));send(post(base),"admin",value,400);
        send(delete(base+"/{id}",id).param("version","1"),"admin",Map.of(),200);
        mvc.perform(get(base+"/{id}",id).with(user("admin"))).andExpect(status().isNotFound());
        value.put("version",0);value.put("lines",List.of(Map.of("seq",4,"cn","재등록 합성 문구")));
        assertThat(send(post(base),"admin",value,200).path("version").asLong()).isEqualTo(3);
        send(delete(base+"/{id}",id).param("version","1"),"admin",Map.of(),409);
        mvc.perform(get(base).param("officeCd","OTHER").with(user("admin"))).andExpect(jsonPath("$.data.totalElements").value(0));
    }
    private Map<String,Object> postInput(String board,boolean published,boolean publicRead,List<String> files){
        return new LinkedHashMap<>(Map.of("board",board,"title","합성 검증 글","body","개인정보 없는 테스트 본문","published",published,
                "publicRead",publicRead,"attachmentIds",files,"expectedVersion",0,"versionLabel","TEST_"+UUID.randomUUID()));
    }
    @Test void accountCreationFailureRollsBackProfileAndCatalog()throws Exception{
        send(post("/api/admin/directory/people"),"admin",Map.of("person",person("rollback_user",true),"initialPassword","short"),400);
        assertThat(catalog.findUser("rollback_user")).isEmpty();
        mvc.perform(get("/api/admin/directory").with(user("admin"))).andExpect(jsonPath("$.data.people[?(@.username == 'rollback_user')]").isEmpty());
        assertThat(repository.load().get("directory")).doesNotContain("rollback_user");
    }
    @Test void disabledUserCannotUseAnExistingAuthenticationAndOwnerCannotChangeOrganization()throws Exception{
        send(post("/api/admin/directory/people"),"admin",Map.of("person",person("inactive_test",true),"initialPassword","SyntheticOnly123!"),200);
        send(post("/api/me/profile"),"inactive_test",Map.of("username","admin","officeCode","FORGED","telephone","000-test"),200);
        mvc.perform(get("/api/me/profile").with(user("inactive_test"))).andExpect(jsonPath("$.data.officeCode").value("HQ")).andExpect(jsonPath("$.data.username").value("inactive_test"));
        send(post("/api/admin/directory/people"),"admin",Map.of("person",person("inactive_test",false)),200);
        mvc.perform(get("/api/bootstrap").with(user("inactive_test"))).andExpect(status().isUnauthorized());
    }
    @Test void privateQuestionAndAttachmentCannotBeReadOrReusedByAnotherUser()throws Exception{
        byte[] bytes="synthetic private text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file=new MockMultipartFile("file","synthetic.txt","text/plain",bytes);
        var uploaded=mvc.perform(multipart("/api/attachments").file(file).with(user("viewer")).with(csrf())).andExpect(status().isOk()).andReturn();
        String attachment=json.readTree(uploaded.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        long id=send(post("/api/posts"),"viewer",postInput("QNA",true,false,List.of(attachment)),200).path("id").asLong();
        mvc.perform(get("/api/posts/"+id).with(user("expired"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/"+attachment).with(user("expired"))).andExpect(status().isForbidden());
        send(post("/api/posts"),"expired",postInput("QNA",true,true,List.of(attachment)),403);
        mvc.perform(get("/api/attachments/"+attachment).with(user("viewer"))).andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(header().string("X-Content-Type-Options","nosniff"));
        send(post("/api/posts/"+id+"/answer"),"viewer",Map.of("answer","조작","version",1),403);
        send(post("/api/posts/"+id+"/answer"),"manager",Map.of("answer","합성 답변","version",1),200);
        mvc.perform(get("/api/posts/"+id).with(user("viewer"))).andExpect(jsonPath("$.data.answer").value("합성 답변"));
    }
    @Test void publishedTermsStayImmutableAndConsentIsIdempotent()throws Exception{
        Map<String,Object> input=postInput("TERMS",true,true,List.of());long id=send(post("/api/posts"),"admin",input,200).path("id").asLong();
        send(post("/api/terms/"+id+"/agreement"),"viewer",Map.of(),200);
        send(post("/api/terms/"+id+"/agreement"),"viewer",Map.of(),200);
        input.put("expectedVersion",1);input.put("body","변경 시도");send(put("/api/posts/"+id),"admin",input,409);
        send(delete("/api/posts/"+id+"?version=1"),"admin",Map.of(),409);
        mvc.perform(get("/api/me/agreements").with(user("viewer"))).andExpect(jsonPath("$.data[?(@.postId == "+id+")].versionLabel").value(input.get("versionLabel")));
        mvc.perform(get("/api/me/agreements").with(user("delegate"))).andExpect(jsonPath("$.data").isEmpty());
        Map<String,Object> future=postInput("TERMS",true,true,List.of());future.put("startDate","2099-01-01");
        long futureId=send(post("/api/posts"),"admin",future,200).path("id").asLong();
        send(post("/api/terms/"+futureId+"/agreement"),"admin",Map.of(),409);
    }
    @Test void personalScheduleUsesOwnerAndRejectsStaleUpdates()throws Exception{
        Map<String,Object> value=new LinkedHashMap<>(Map.of("name","합성 일정","body","테스트","category","TEST","status","OPEN","start","2026-09-15T09:00:00","end","2026-09-15T10:00:00","version",0,"username","admin"));
        JsonNode result=send(post("/api/me/schedules"),"viewer",value,200);long id=result.path("id").asLong();assertThat(result.path("username").asText()).isEqualTo("viewer");
        mvc.perform(get("/api/me/schedules?from=2026-09-01&to=2026-09-30").with(user("delegate"))).andExpect(jsonPath("$.data").isEmpty());
        value.put("version",1);send(put("/api/me/schedules/"+id),"delegate",value,403);
        send(put("/api/me/schedules/"+id),"viewer",value,200);send(put("/api/me/schedules/"+id),"viewer",value,409);
        send(delete("/api/me/schedules/"+id+"?version=2"),"viewer",Map.of(),200);
    }
    @Test void referenceDataEnforcesBankRelationshipsVersionsAndParameterPeriods()throws Exception{
        String base="/api/admin/reference-data/";
        mvc.perform(get("/api/admin/reference-data").with(user("viewer"))).andExpect(status().isForbidden());
        Map<String,String> branch=Map.of("bankCd","TESTBANK","brCd","TESTBR","brNm","가상 지점","useFlag","Y");
        send(post(base+"bank-branches"),"admin",Map.of("values",branch,"expectedVersion",0),409);
        Map<String,String> bank=new LinkedHashMap<>(Map.of("bankCd","TESTBANK","bankNm","=SUM(1,2)\n합성 은행","useFlag","Y"));
        send(post(base+"banks"),"admin",Map.of("values",bank,"expectedVersion",0),200);
        send(post(base+"bank-branches"),"admin",Map.of("values",branch,"expectedVersion",0),200);
        mvc.perform(get(base+"banks/export").with(user("admin"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"'=SUM(1,2)")));
        bank.put("useFlag","N");send(post(base+"banks"),"admin",Map.of("values",bank,"expectedVersion",1),200);
        send(post(base+"banks"),"admin",Map.of("values",bank,"expectedVersion",1),409);
        mvc.perform(get(base+"bank-branches").with(user("admin"))).andExpect(jsonPath("$.data.content[?(@.values.bankCd == 'TESTBANK')].values.useFlag").value("N"));
        Map<String,String> parameter=new LinkedHashMap<>(Map.of("prmtSeCd","TEST_PERIOD","appStartDay","2026-01-01","appEndDay","2026-12-31","prmt","합성 매개변수","prmtVal","1"));
        send(post(base+"parameters"),"admin",Map.of("values",parameter,"expectedVersion",0),200);
        parameter.put("appStartDay","2026-06-01");send(post(base+"parameters"),"admin",Map.of("values",parameter,"expectedVersion",0),409);
    }
    @Test void operationNumberingAttachmentsCompletionAndHistoryAreConsistent()throws Exception{
        mvc.perform(get("/api/admin/operations/definitions").with(user("viewer"))).andExpect(status().isForbidden());
        var uploaded=mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file","task-test.txt","text/plain",new byte[]{1,2,3})).with(user("admin")).with(csrf())).andExpect(status().isOk()).andReturn();
        String file=json.readTree(uploaded.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        Map<String,String> fields=new LinkedHashMap<>(Map.of("taskTitle","가상 과업","taskSeCd","TEST","taskYm","2026-09","sysSeCd","INFO","reqDay","2026-09-01","reqMemo","합성 요청","procStatusCd","10"));
        Map<String,Object> payload=new LinkedHashMap<>(Map.of("type","TASK","values",fields,"requestFiles",List.of(file),"responseFiles",List.of(),"version",0));
        JsonNode first=send(post("/api/admin/operations"),"admin",payload,200);long id=first.path("id").asLong();assertThat(first.path("number").asLong()).isEqualTo(1);
        JsonNode second=send(post("/api/admin/operations"),"admin",payload,200);assertThat(second.path("number").asLong()).isEqualTo(2);
        mvc.perform(get("/api/attachments/"+file).with(user("expired"))).andExpect(status().isForbidden());
        send(delete("/api/attachments/"+file),"admin",Map.of(),409);
        payload.put("version",1);fields.put("taskYm","2026-10");send(put("/api/admin/operations/"+id),"admin",payload,409);
        fields.put("taskYm","2026-09");fields.put("procStatusCd","20");send(put("/api/admin/operations/"+id),"admin",payload,400);
        fields.put("cmpltDay","2026-09-02");send(put("/api/admin/operations/"+id),"admin",payload,200);
        send(delete("/api/admin/operations/"+id+"?version=1"),"admin",Map.of(),409);
        send(delete("/api/admin/operations/"+id+"?version=2"),"admin",Map.of(),200);
        send(delete("/api/admin/operations/"+second.path("id").asLong()+"?version=1"),"admin",Map.of(),200);
        mvc.perform(get("/api/attachments/"+file).with(user("admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/operations/"+id+"/history").with(user("admin"))).andExpect(jsonPath("$.data.length()").value(3)).andExpect(jsonPath("$.data[2].action").value("DELETED"));
    }
    @Test void sessionMonitorHidesSessionTokenAndExpirationBlocksNextApiRequest()throws Exception{
        var login=mvc.perform(post("/login").with(csrf()).param("username","viewer").param("password","viewer123!"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/api/bootstrap").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/sessions").with(user("expired"))).andExpect(status().isForbidden());
        var listed=mvc.perform(get("/api/admin/sessions").with(user("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var sessions=json.readTree(listed).path("data");
        String reference="";for(JsonNode row:sessions)if(row.path("username").asText().equals("viewer"))reference=row.path("reference").asText();
        assertThat(reference).matches("[a-f0-9]{64}").isNotEqualTo(session.getId());
        assertThat(json.readTree(listed).path("data").get(0).has("sessionId")).isFalse();
        send(post("/api/admin/sessions/"+reference+"/expire"),"expired",Map.of(),403);
        send(post("/api/admin/sessions/"+reference+"/expire"),"admin",Map.of(),200);
        mvc.perform(get("/api/bootstrap").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void bulkRevocationValidatesWholePlanAndPreservesOtherSystemAndDelegation()throws Exception{
        send(post("/api/admin/directory/people"),"admin",Map.of("person",person("bulk_user",true)),200);
        catalog.saveAuthority(new AuthorizationCatalog.AuthorityDefinition("AUTH_OTHER_SYSTEM","합성 별도 시스템",true,"OTHER",null,""));
        var from=java.time.LocalDate.of(2026,1,1);var to=java.time.LocalDate.of(2099,12,31);
        catalog.saveAssignment("bulk_user","AUTH_VIEWER",AuthorizationCatalog.AssignmentType.DIRECT,from,to,null);
        catalog.saveAssignment("bulk_user","AUTH_OTHER_SYSTEM",AuthorizationCatalog.AssignmentType.DIRECT,from,to,null);
        catalog.saveAssignment("bulk_user","AUTH_CONTENT_MANAGER",AuthorizationCatalog.AssignmentType.DELEGATED,from,to,"manager");
        long version=catalog.authorityVersion();
        Map<String,Object> payload=new LinkedHashMap<>(Map.of("targets",List.of(Map.of("username","bulk_user","organizationId","HQ"),Map.of("username","expired","organizationId","STALE")),"systemId","INFO","includeDelegated",false,"expectedVersion",version,"reason","합성 일괄 회수 검증"));
        send(post("/api/admin/authority-revocations"),"admin",payload,409);
        assertThat(catalog.authorityVersion()).isEqualTo(version);
        payload.put("targets",List.of(Map.of("username","bulk_user","organizationId","HQ")));
        JsonNode preview=send(post("/api/admin/authority-revocations/preview"),"admin",payload,200);assertThat(preview.path("assignments").size()).isEqualTo(1);
        send(post("/api/admin/authority-revocations"),"expired",payload,403);
        JsonNode result=send(post("/api/admin/authority-revocations"),"admin",payload,200);assertThat(result.path("assignments").get(0).path("status").asText()).isEqualTo("REVOKED");
        assertThat(catalog.assignmentsFor("bulk_user")).filteredOn(a->a.authorityId().equals("AUTH_OTHER_SYSTEM")).allMatch(a->a.status()==AuthorizationCatalog.AssignmentStatus.APPROVED);
        assertThat(catalog.assignmentsFor("bulk_user")).filteredOn(a->a.type()==AuthorizationCatalog.AssignmentType.DELEGATED).allMatch(a->a.status()==AuthorizationCatalog.AssignmentStatus.APPROVED);
        send(post("/api/admin/authority-revocations"),"admin",payload,409);
        mvc.perform(get("/api/me/notices/unread-count").with(user("bulk_user"))).andExpect(jsonPath("$.data.count").value(1));
    }
    @Test void helpGuideEnforcesMenuAccessAndKeepsHistoricalAttachmentsForManagers()throws Exception{
        var uploaded=mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file","help-test.txt","text/plain",new byte[]{4,5,6})).with(user("admin")).with(csrf())).andExpect(status().isOk()).andReturn();
        String file=json.readTree(uploaded.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        Map<String,Object> value=new LinkedHashMap<>(Map.of("menuId","SYSTEM_AUTH","type","04","title","합성 관리 도움말","majorFunctions","합성 기능 설명","active",true,"files",List.of(file),"screenFiles",List.of(),"version",0));
        long privateId=send(post("/api/help"),"admin",value,200).path("id").asLong();
        mvc.perform(get("/api/help/"+privateId).with(user("expired"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/"+file).with(user("expired"))).andExpect(status().isForbidden());
        send(post("/api/help"),"expired",value,403);
        value.put("menuId","CONTENT_LIST");value.put("title","합성 사용자 도움말");
        long id=send(post("/api/help"),"admin",value,200).path("id").asLong();
        mvc.perform(get("/api/help/"+id).with(user("expired"))).andExpect(status().isOk());
        send(post("/api/help/"+id+"/use"),"expired",Map.of(),200);
        value.put("version",1);value.put("title","합성 사용자 도움말 개정");value.put("files",List.of());
        send(put("/api/help/"+id),"admin",value,200);
        mvc.perform(get("/api/help/"+id+"/history").with(user("expired"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/help/"+id+"/history").with(user("admin"))).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].title").value("합성 사용자 도움말"));
        mvc.perform(get("/api/attachments/"+file).with(user("expired"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/"+file).with(user("admin"))).andExpect(status().isOk());
        send(delete("/api/attachments/"+file),"admin",Map.of(),409);
    }
}
