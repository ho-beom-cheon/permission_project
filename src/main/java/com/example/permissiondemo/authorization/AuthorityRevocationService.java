package com.example.permissiondemo.authorization;

import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.common.InboxService;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 선택 사용자·현재 조직·시스템의 승인 권한을 미리 확인한 뒤 한 번에 회수한다. */
@Service
@StateBoundary
public class AuthorityRevocationService {
    private final AuthorizationCatalog catalog;
    private final AuditEventService audit;
    private final InboxService inbox;
    public AuthorityRevocationService(AuthorizationCatalog catalog,AuditEventService audit,InboxService inbox){this.catalog=catalog;this.audit=audit;this.inbox=inbox;}
    public Plan preview(Request request){synchronized(catalog){return plan(request);}}
    public Plan revoke(Request request){
        synchronized(catalog){
            if(request==null||request.reason()==null||request.reason().isBlank()||request.reason().length()>1000)throw new IllegalArgumentException("회수 사유는 1~1000자로 입력해 주세요.");
            Plan plan=plan(request);if(plan.assignments().isEmpty())throw new ApiException(ErrorCode.CONFLICT,"회수할 승인 권한이 없습니다.");
            List<AuthorizationCatalog.AuthorityAssignment> revoked=new ArrayList<>();
            for(var assignment:plan.assignments())revoked.add(catalog.revokeAssignment(assignment.username(),assignment.authorityId(),assignment.type()));
            for(String username:plan.assignments().stream().map(AuthorizationCatalog.AuthorityAssignment::username).distinct().toList()){
                inbox.deliver(username,"AUTHORITY_REVOKE:"+catalog.authorityVersion(),"업무 권한이 회수되었습니다.",request.systemId()+" · "+request.reason().trim(),"/authority-requests.html");
            }
            audit.record("AUTHORITY_BULK_REVOKED","SYSTEM",request.systemId(),"SUCCESS",Map.of("count",plan.assignments().size(),"reason",request.reason().trim()));
            return new Plan(catalog.authorityVersion(),List.copyOf(revoked));
        }
    }
    private Plan plan(Request request){
        if(request==null||request.targets()==null||request.targets().isEmpty()||request.targets().size()>100||request.targets().stream().anyMatch(t->t==null||t.username()==null||t.username().isBlank()||t.organizationId()==null||t.organizationId().isBlank())
                ||request.targets().stream().map(Target::username).distinct().count()!=request.targets().size()
                ||request.systemId()==null||!request.systemId().matches("[A-Z0-9_]{1,50}"))throw new IllegalArgumentException("시스템과 중복 없는 사용자 1~100명을 선택해 주세요.");
        if(request.expectedVersion()!=catalog.authorityVersion())throw new ApiException(ErrorCode.CONFLICT,"권한이 변경됐습니다. 다시 조회하고 회수 대상을 확인해 주세요.");
        List<AuthorizationCatalog.AuthorityAssignment> result=new ArrayList<>();
        for(Target target:request.targets()){
            var user=catalog.findUser(target.username()).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,target.username()));
            if(!user.organizationId().equals(target.organizationId()))throw new ApiException(ErrorCode.CONFLICT,"선택 사용자의 조직이 변경됐습니다.");
            Map<String,AuthorizationCatalog.AuthorityAssignment> latest=new HashMap<>();
            catalog.assignmentsFor(user.username()).stream().filter(a->a.organizationId().equals(user.organizationId()))
                    .filter(a->request.includeDelegated()||a.type()==AuthorizationCatalog.AssignmentType.DIRECT)
                    .filter(a->catalog.findAuthority(a.authorityId()).map(d->d.systemId().equals(request.systemId())).orElse(false))
                    .forEach(a->latest.merge(a.authorityId()+":"+a.type(),a,(left,right)->left.sequence()>right.sequence()?left:right));
            result.addAll(latest.values().stream().filter(a->a.status()==AuthorizationCatalog.AssignmentStatus.APPROVED).toList());
        }
        if(result.stream().anyMatch(a->a.username().equals("admin")&&a.authorityId().equals(AuthorizationCatalog.AUTH_SYSTEM_ADMIN)&&a.type()==AuthorizationCatalog.AssignmentType.DIRECT))throw new ApiException(ErrorCode.CONFLICT,"기본 관리자의 필수 권한은 회수할 수 없습니다.");
        return new Plan(catalog.authorityVersion(),result.stream().sorted(Comparator.comparing(AuthorizationCatalog.AuthorityAssignment::username).thenComparing(AuthorizationCatalog.AuthorityAssignment::authorityId).thenComparing(a->a.type().name())).toList());
    }
    public record Target(String username,String organizationId) { }
    public record Request(List<Target> targets,String systemId,boolean includeDelegated,long expectedVersion,String reason) { }
    public record Plan(long authorityVersion,List<AuthorizationCatalog.AuthorityAssignment> assignments) { }
}
