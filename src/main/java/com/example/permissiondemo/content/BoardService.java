package com.example.permissiondemo.content;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 게시판 구분과 사용·공지·답변·첨부 정책을 관리한다. 사용 중지는 글과 이력을 보존한다. */
@Service
@StateBoundary
public class BoardService implements StateParticipant {
    private final Map<String, Definition> definitions=new TreeMap<>();
    private final CurrentUserContext current;
    private final ProgramAuthorizationService authorization;
    private final AuditEventService audit;
    private final Clock clock;
    public BoardService(CurrentUserContext current,ProgramAuthorizationService authorization,AuditEventService audit,Clock clock){
        this.current=current;this.authorization=authorization;this.audit=audit;this.clock=clock;
        Map<ContentService.Board,String> names=Map.of(ContentService.Board.NOTICE,"공지사항",ContentService.Board.FAQ,"자주 묻는 질문",ContentService.Board.QNA,"질문과 답변",ContentService.Board.DOCUMENT,"자료실",ContentService.Board.TERMS,"이용약관");
        names.forEach((type,name)->definitions.put(type.name(),new Definition(type.name(),type,name,"","COMMON",true,
                type==ContentService.Board.NOTICE,type==ContentService.Board.QNA,10,1,"SYSTEM",Instant.EPOCH)));
    }
    public List<Definition> list(){current.require();boolean manager=canManage();return definitions.values().stream().filter(d->d.active()||manager).toList();}
    public Definition definition(String id){Definition value=definitions.get(id);if(value==null)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"게시판을 찾을 수 없습니다.");return value;}
    public Definition save(WriteDefinition request){
        if(!canManage())throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(request==null||request.id()==null||!request.id().matches("[A-Z0-9_]{1,50}")||request.type()==null
                ||request.name()==null||request.name().isBlank()||request.name().length()>100
                ||request.description()!=null&&request.description().length()>2000
                ||request.systemId()==null||!request.systemId().matches("[A-Z0-9_]{1,50}")
                ||request.maxAttachments()<0||request.maxAttachments()>10
                ||request.answerEnabled()&&request.type()!=ContentService.Board.QNA)throw new IllegalArgumentException("게시판 코드·유형·이름·시스템·첨부 수를 확인해 주세요. 답변은 질문답변 유형에서 사용합니다.");
        Definition previous=definitions.get(request.id());
        if(request.version()!=(previous==null?0:previous.version())||previous!=null&&previous.type()!=request.type())throw new ApiException(ErrorCode.CONFLICT,"게시판 유형을 바꿀 수 없거나 다른 변경이 있습니다. 다시 조회해 주세요.");
        Definition saved=new Definition(request.id(),request.type(),request.name().trim(),Objects.toString(request.description(),""),request.systemId(),request.active(),request.noticeEnabled(),request.answerEnabled(),request.maxAttachments(),request.version()+1,current.require().username(),Instant.now(clock));
        definitions.put(saved.id(),saved);audit.record("BOARD_SAVED","BOARD",saved.id(),"SUCCESS",Map.of("version",saved.version()));return saved;
    }
    public void deactivate(String id,long version){
        if(!canManage())throw new ApiException(ErrorCode.ACCESS_DENIED);
        Definition previous=definition(id);
        if(previous.version()!=version)throw new ApiException(ErrorCode.CONFLICT,"게시판이 변경됐습니다. 다시 조회해 주세요.");
        definitions.put(id,new Definition(previous.id(),previous.type(),previous.name(),previous.description(),previous.systemId(),false,previous.noticeEnabled(),previous.answerEnabled(),previous.maxAttachments(),version+1,current.require().username(),Instant.now(clock)));
        audit.record("BOARD_DEACTIVATED","BOARD",id,"SUCCESS",Map.of("version",version+1));
    }
    private boolean canManage(){return authorization.isAllowed(current.authentication(),"CONTENT_LIST","CONTENT","CONTENT_PUBLISH");}
    public record WriteDefinition(String id,ContentService.Board type,String name,String description,String systemId,boolean active,boolean noticeEnabled,boolean answerEnabled,int maxAttachments,long version) { }
    public record Definition(String id,ContentService.Board type,String name,String description,String systemId,boolean active,boolean noticeEnabled,boolean answerEnabled,int maxAttachments,long version,String updatedBy,Instant updatedAt) { }
    public record StoredState(Map<String,Definition> definitions) { }
    @Override public String stateKey(){return "boards";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){return new StoredState(definitions);}
    @Override public void restoreState(Object raw){definitions.clear();definitions.putAll(((StoredState)raw).definitions());}
}
