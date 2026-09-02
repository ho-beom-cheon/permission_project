package com.example.permissiondemo.content;

import java.time.*;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.MenuAuthorizationService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 메뉴별 도움말, 본문/화면설명 첨부, 버전별 원문, 사용 기록을 관리한다. */
@Service
@StateBoundary
public class HelpGuideService implements StateParticipant,AttachmentUse {
    private final Map<Long,Guide> guides=new TreeMap<>();
    private final Map<Long,List<Guide>> history=new TreeMap<>();
    private final Map<Long,Long> views=new TreeMap<>();
    private long sequence;
    private final AuthorizationCatalog catalog;
    private final MenuAuthorizationService menus;
    private final ProgramAuthorizationService permissions;
    private final CurrentUserContext current;
    private final AttachmentRepository attachments;
    private final AuditEventService audit;
    private final Clock clock;
    public HelpGuideService(AuthorizationCatalog catalog,MenuAuthorizationService menus,ProgramAuthorizationService permissions,CurrentUserContext current,AttachmentRepository attachments,AuditEventService audit,Clock clock){
        this.catalog=catalog;this.menus=menus;this.permissions=permissions;this.current=current;this.attachments=attachments;this.audit=audit;this.clock=clock;
    }
    public PageResult<Guide> list(String menuId,String query,PageQuery page){
        current.require();String search=Objects.toString(query,"").trim().toLowerCase(Locale.ROOT);
        return PageResult.of(guides.values().stream().filter(g->!g.deleted()&&visible(g)).filter(g->menuId==null||menuId.isBlank()||g.menuId().equals(menuId))
                .filter(g->g.title().toLowerCase(Locale.ROOT).contains(search)||g.majorFunctions().toLowerCase(Locale.ROOT).contains(search))
                .sorted(Comparator.comparing(Guide::updatedAt).reversed()).toList(),page);
    }
    public Guide get(long id){Guide guide=require(id);if(!visible(guide))throw new ApiException(ErrorCode.ACCESS_DENIED);return guide;}
    public Guide save(long id,WriteGuide value){
        requireManage();Guide old=id==0?null:require(id);
        if(old!=null&&old.deleted()||(old==null?0:old.version())!=value.version())throw new ApiException(ErrorCode.CONFLICT,id);
        if(value.menuId()==null||catalog.menus().stream().noneMatch(menu->menu.id().equals(value.menuId())&&menu.active())||value.type()==null||!value.type().matches("[A-Za-z0-9_]{1,30}"))throw new IllegalArgumentException("메뉴와 도움말 구분을 확인해 주세요.");
        if(old!=null&&(!old.menuId().equals(value.menuId())||!old.type().equals(value.type())))throw new ApiException(ErrorCode.CONFLICT,"메뉴와 도움말 구분은 변경할 수 없습니다.");
        if(guides.values().stream().anyMatch(g->g.id()!=id&&!g.deleted()&&g.menuId().equals(value.menuId())&&g.type().equals(value.type())))throw new ApiException(ErrorCode.CONFLICT,"이 메뉴·구분의 도움말이 이미 있습니다.");
        text(value.title(),200,true);text(value.majorFunctions(),50000,true);text(value.cautions(),20000,false);text(value.references(),20000,false);text(value.regulations(),20000,false);
        List<String> files=validateFiles(value.files(),old),screens=validateFiles(value.screenFiles(),old);
        Instant now=Instant.now(clock);String actor=current.require().username();long key=old==null?++sequence:id;
        Guide saved=new Guide(key,value.menuId(),value.type(),value.title().trim(),value.majorFunctions(),Objects.toString(value.cautions(),""),Objects.toString(value.references(),""),Objects.toString(value.regulations(),""),value.active(),files,screens,value.version()+1,old==null?actor:old.createdBy(),old==null?now:old.createdAt(),actor,now,false);
        guides.put(key,saved);history.computeIfAbsent(key,ignored->new ArrayList<>()).add(saved);audit.record("HELP_SAVED","HELP",String.valueOf(key),"SUCCESS",Map.of("version",saved.version()));return saved;
    }
    public void delete(long id,long version){requireManage();Guide old=require(id);if(old.deleted()||old.version()!=version)throw new ApiException(ErrorCode.CONFLICT,id);
        Guide deleted=new Guide(old.id(),old.menuId(),old.type(),old.title(),old.majorFunctions(),old.cautions(),old.references(),old.regulations(),false,old.files(),old.screenFiles(),old.version()+1,old.createdBy(),old.createdAt(),current.require().username(),Instant.now(clock),true);
        guides.put(id,deleted);history.get(id).add(deleted);audit.record("HELP_DELETED","HELP",String.valueOf(id),"SUCCESS",Map.of());
    }
    public List<Guide> history(long id){requireManage();require(id);return List.copyOf(history.getOrDefault(id,List.of()));}
    public long recordUse(long id){Guide guide=get(id);long count=views.merge(id,1L,Long::sum);audit.record("HELP_VIEWED","HELP",String.valueOf(guide.id()),"SUCCESS",Map.of("version",guide.version()));return count;}
    public Map<Long,Long> summary(){requireManage();return Map.copyOf(views);}
    private boolean visible(Guide guide){if(guide.deleted())return false;return canManage()||guide.active()&&menus.canAccessMenu(current.authentication(),guide.menuId());}
    private boolean canManage(){return permissions.isAllowed(current.authentication(),"SYSTEM_AUTH","AUTHORITY","AUTHORITY_UPDATE");}
    private void requireManage(){current.require();if(!canManage())throw new ApiException(ErrorCode.ACCESS_DENIED);}
    private Guide require(long id){Guide value=guides.get(id);if(value==null)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id);return value;}
    private void text(String value,int max,boolean required){if(required&&(value==null||value.isBlank())||value!=null&&value.length()>max)throw new IllegalArgumentException("도움말 필수 항목과 길이를 확인해 주세요.");}
    private boolean linked(Guide guide,String id){return guide.files().contains(id)||guide.screenFiles().contains(id);}
    private List<String> validateFiles(List<String> values,Guide old){List<String> ids=values==null?List.of():values.stream().distinct().toList();if(ids.size()>10)throw new IllegalArgumentException("첨부 구분별 최대 10개까지 등록할 수 있습니다.");
        for(String id:ids){var file=attachments.metadata(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id));if(!file.owner().equals(current.require().username())&&(old==null||!linked(old,id)))throw new ApiException(ErrorCode.ACCESS_DENIED);}return ids;
    }
    @Override public boolean isLinked(String id){return history.values().stream().flatMap(Collection::stream).anyMatch(g->linked(g,id));}
    @Override public boolean canReadAttachment(String id){
        if(canManage())return isLinked(id);
        return guides.values().stream().anyMatch(g->linked(g,id)&&visible(g));
    }
    public record WriteGuide(String menuId,String type,String title,String majorFunctions,String cautions,String references,String regulations,boolean active,List<String> files,List<String> screenFiles,long version) { }
    public record Guide(long id,String menuId,String type,String title,String majorFunctions,String cautions,String references,String regulations,boolean active,List<String> files,List<String> screenFiles,long version,String createdBy,Instant createdAt,String updatedBy,Instant updatedAt,boolean deleted) { }
    @Override public String stateKey(){return "help-guides";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){Map<Long,List<Guide>> versions=new TreeMap<>();history.forEach((id,list)->versions.put(id,List.copyOf(list)));return new StoredState(List.copyOf(guides.values()),versions,Map.copyOf(views),sequence);}
    @Override public void restoreState(Object raw){StoredState state=(StoredState)raw;guides.clear();state.guides().forEach(g->guides.put(g.id(),g));history.clear();state.history().forEach((id,list)->history.put(id,new ArrayList<>(list)));views.clear();views.putAll(state.views());sequence=state.sequence();}
    public record StoredState(List<Guide> guides,Map<Long,List<Guide>> history,Map<Long,Long> views,long sequence) { }
}
