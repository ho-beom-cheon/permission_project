package com.example.permissiondemo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/** 세션 토큰 원문을 노출하지 않고 현재 서버의 로그인 세션을 조회·만료 처리한다. */
@Service
public class SessionMonitorService {
    private final SessionRegistry registry;
    private final CurrentUserContext current;
    private final ProgramAuthorizationService permissions;
    private final AuditEventService audit;
    public SessionMonitorService(SessionRegistry registry,CurrentUserContext current,ProgramAuthorizationService permissions,AuditEventService audit){this.registry=registry;this.current=current;this.permissions=permissions;this.audit=audit;}
    public List<SessionView> list(){require("AUTHORITY_READ");return sessions().stream().map(s->new SessionView(reference(s.getSessionId()),username(s.getPrincipal()),s.getLastRequest().toInstant(),s.isExpired())).sorted(Comparator.comparing(SessionView::lastRequest).reversed()).toList();}
    public void expire(String reference){
        require("AUTHORITY_UPDATE");
        SessionInformation session=sessions().stream().filter(s->reference(s.getSessionId()).equals(reference)).findFirst().orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,reference));
        // 감사 저장이 실패하면 세션을 먼저 종료하지 않는다.
        audit.record("SESSION_EXPIRED_BY_ADMIN","USER_SESSION",reference,"SUCCESS",Map.of("username",username(session.getPrincipal())));
        session.expireNow();
    }
    private List<SessionInformation> sessions(){return registry.getAllPrincipals().stream().flatMap(p->registry.getAllSessions(p,true).stream()).toList();}
    private String username(Object principal){return principal instanceof UserDetails user?user.getUsername():String.valueOf(principal);}
    private String reference(String id){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private void require(String action){current.require();if(!permissions.isAllowed(current.authentication(),"SYSTEM_AUTH","AUTHORITY",action))throw new ApiException(ErrorCode.ACCESS_DENIED);}
    public record SessionView(String reference,String username,Instant lastRequest,boolean expired) { }
}
