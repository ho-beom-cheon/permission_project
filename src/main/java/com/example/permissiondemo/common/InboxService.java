package com.example.permissiondemo.common;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 프로그램 내부 알림과 읽음 상태. 메일·문자 발송 성공으로 취급하지 않는다. */
@Service
@StateBoundary
public class InboxService implements StateParticipant {
    private final Map<Long,Notice> notices=new TreeMap<>();
    private long sequence;
    private final AuthorizationCatalog catalog;
    private final CurrentUserContext current;
    private final AuditEventService audit;
    private final Clock clock;
    public InboxService(AuthorizationCatalog catalog,CurrentUserContext current,AuditEventService audit,Clock clock){this.catalog=catalog;this.current=current;this.audit=audit;this.clock=clock;}
    public void deliver(String username,String eventKey,String title,String body,String path){
        if(catalog.findUser(username).isEmpty())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,username);
        if(eventKey==null||eventKey.isBlank()||eventKey.length()>100||title==null||title.isBlank()||title.length()>200||body==null||body.length()>2000
                ||!Set.of("/authority-requests.html","/my-page.html","/operations.html").contains(path))throw new IllegalArgumentException("알림 항목을 확인해 주세요.");
        if(notices.values().stream().anyMatch(n->n.username().equals(username)&&n.eventKey().equals(eventKey)))return;
        Notice value=new Notice(++sequence,username,eventKey,title,body,path,Instant.now(clock),null);notices.put(value.id(),value);
    }
    public PageResult<Notice> list(boolean unreadOnly,PageQuery page){String actor=current.require().username();return PageResult.of(notices.values().stream().filter(n->n.username().equals(actor)&&(!unreadOnly||n.readAt()==null)).sorted(Comparator.comparingLong(Notice::id).reversed()).toList(),page);}
    public long unreadCount(){String actor=current.require().username();return notices.values().stream().filter(n->n.username().equals(actor)&&n.readAt()==null).count();}
    public Notice markRead(long id){Notice old=notices.get(id);if(old==null)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id);if(!old.username().equals(current.require().username()))throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(old.readAt()!=null)return old;Notice saved=new Notice(old.id(),old.username(),old.eventKey(),old.title(),old.body(),old.path(),old.createdAt(),Instant.now(clock));notices.put(id,saved);audit.record("NOTICE_READ","NOTICE",String.valueOf(id),"SUCCESS",Map.of());return saved;
    }
    public record Notice(long id,String username,String eventKey,String title,String body,String path,Instant createdAt,Instant readAt) { }
    @Override public String stateKey(){return "inbox";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){return new StoredState(List.copyOf(notices.values()),sequence);}
    @Override public void restoreState(Object raw){StoredState state=(StoredState)raw;notices.clear();state.notices().forEach(n->notices.put(n.id(),n));sequence=state.sequence();}
    public record StoredState(List<Notice> notices,long sequence) { }
}
