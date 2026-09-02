package com.example.permissiondemo.operations;

import java.time.*;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.ReferenceDataService.Definition;
import com.example.permissiondemo.common.ReferenceDataService.Field;
import com.example.permissiondemo.content.AttachmentRepository;
import com.example.permissiondemo.content.AttachmentUse;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 과업·민원과 이슈의 요청/처리 내용, 분리된 첨부, 변경 이력을 관리한다. 외부 처리를 실행하지 않는다. */
@Service
@StateBoundary
public class OperationWorkService implements StateParticipant, AttachmentUse {
    private final Map<String,Definition> definitions=new LinkedHashMap<>();
    private final Map<Long,Work> works=new TreeMap<>();
    private final Map<String,Long> numbers=new TreeMap<>();
    private final Map<Long,List<Change>> history=new TreeMap<>();
    private long sequence;
    private final CurrentUserContext current;
    private final ProgramAuthorizationService permissions;
    private final AttachmentRepository attachments;
    private final AuditEventService audit;
    private final Clock clock;
    public OperationWorkService(CurrentUserContext current,ProgramAuthorizationService permissions,AttachmentRepository attachments,AuditEventService audit,Clock clock){
        this.current=current;this.permissions=permissions;this.attachments=attachments;this.audit=audit;this.clock=clock;
        define("TASK","과업·민원","taskSeCd,taskYm",
                "taskTitle|제목|text|!","taskSeCd|과업 구분 코드|code|!","taskYm|과업 연월|month|!","sysSeCd|시스템 구분|code|!","jobSeCd|업무 구분|code","jobTypeSeCd|업무 유형|code","officeCd|사업소 코드|code",
                "reqDay|요청일|date|!","reqerNm|요청자 이름|text","reqerTel|요청자 전화|text","reqMemo|요청 내용|textarea|!","cmpltTlimitDay|완료 기한|date","startDay|시작일|date","cmpltDay|완료일|date","procStatusCd|처리 상태 코드|code|!","opetrNm|처리자 이름|text","procCn|처리 내용|textarea","etcMemo|기타 메모|textarea","mkey|관리 키|text");
        define("ISSUE","이슈","",
                "issueTitle|제목|text|!","sysSeCd|시스템 구분|code|!","upperJobSe|상위 업무 구분|code","pgmId|프로그램 ID|code","menuId|메뉴 ID|code","prrtyRnk|우선 순위|number|!","issueTypeSeCd|이슈 유형 코드|code|!","issueCn|요청 내용|textarea|!","issueProcStatusSeCd|처리 상태 코드|code|!","issueProcCn|처리 내용|textarea","procDt|처리일|date","issueRegerNm|등록자 이름|text","issueRegerCnpl|등록자 연락처|text","opetrNm|처리자 이름|text");
    }
    private void define(String id,String name,String keys,String...specs){
        definitions.put(id,new Definition(id,name,keys.isEmpty()?List.of():List.of(keys.split(",")),Arrays.stream(specs).map(spec->{String[] parts=spec.split("\\|");return new Field(parts[0],parts[1],parts[2],parts.length>3);}).toList()));
    }
    public List<Definition> definitions(){requireRead();return List.copyOf(definitions.values());}
    public PageResult<Work> list(String type,String query,PageQuery page){
        requireRead();definition(type);String search=Objects.toString(query,"").trim().toLowerCase(Locale.ROOT);
        return PageResult.of(works.values().stream().filter(w->!w.deleted()&&w.type().equals(type))
                .filter(w->search.isEmpty()||w.values().values().stream().anyMatch(v->v.toLowerCase(Locale.ROOT).contains(search)))
                .sorted(Comparator.comparing(Work::updatedAt).reversed()).toList(),page);
    }
    public Work save(long id,WriteWork value){
        requireWrite();Definition definition=definition(value.type());Work old=id==0?null:require(id);
        if(old!=null&&(old.deleted()||!old.type().equals(value.type()))||(old==null?0:old.version())!=value.version())throw new ApiException(ErrorCode.CONFLICT,id);
        Map<String,String> clean=validate(definition,value.values());
        if(old!=null&&definition.keyFields().stream().anyMatch(key->!old.values().get(key).equals(clean.get(key))))throw new ApiException(ErrorCode.CONFLICT,"과업 구분과 연월은 변경할 수 없습니다.");
        if(value.type().equals("TASK")){
            period(clean,"reqDay","cmpltDay");period(clean,"startDay","cmpltDay");
            if("20".equals(clean.get("procStatusCd"))&&clean.get("cmpltDay").isBlank())throw new IllegalArgumentException("완료 상태에는 완료일이 필요합니다.");
        }
        List<String> requestFiles=validateFiles(value.requestFiles(),old),responseFiles=validateFiles(value.responseFiles(),old);
        if(requestFiles.size()+responseFiles.size()>20)throw new IllegalArgumentException("요청·처리 첨부파일은 합계 20개 이하여야 합니다.");
        String actor=current.require().username();Instant now=Instant.now(clock);long key=old==null?++sequence:id;
        String numbering=value.type().equals("TASK")?"TASK:"+clean.get("taskSeCd")+":"+clean.get("taskYm"):"ISSUE";
        long number=old==null?numbers.merge(numbering,1L,Long::sum):old.number();
        Work saved=new Work(key,value.type(),number,Map.copyOf(clean),requestFiles,responseFiles,old==null?actor:old.createdBy(),old==null?now:old.createdAt(),actor,now,value.version()+1,false);
        works.put(key,saved);recordChange(saved,"SAVED");return saved;
    }
    public void delete(long id,long version){requireWrite();Work old=require(id);if(old.deleted()||old.version()!=version)throw new ApiException(ErrorCode.CONFLICT,id);
        Work deleted=new Work(old.id(),old.type(),old.number(),old.values(),old.requestFiles(),old.responseFiles(),old.createdBy(),old.createdAt(),current.require().username(),Instant.now(clock),old.version()+1,true);
        works.put(id,deleted);recordChange(deleted,"DELETED");
    }
    public List<Change> history(long id){requireRead();require(id);return List.copyOf(history.getOrDefault(id,List.of()));}
    private void recordChange(Work work,String action){
        String state=work.values().get(work.type().equals("TASK")?"procStatusCd":"issueProcStatusSeCd");
        history.computeIfAbsent(work.id(),ignored->new ArrayList<>()).add(new Change(work.version(),action,state,work.updatedBy(),work.updatedAt()));
        audit.record("OPERATION_"+action,work.type(),String.valueOf(work.id()),"SUCCESS",Map.of("version",work.version()));
    }
    private Map<String,String> validate(Definition definition,Map<String,String> values){
        if(values==null||values.size()>definition.fields().size())throw new IllegalArgumentException("입력 항목을 확인해 주세요.");Map<String,String> clean=new LinkedHashMap<>();
        for(Field field:definition.fields()){
            String text=Objects.toString(values.get(field.id()),"").trim();
            if(field.required()&&text.isBlank()||text.length()>(field.type().equals("textarea")?20000:250))throw new IllegalArgumentException(field.label()+" 항목의 필수 여부와 길이를 확인해 주세요.");
            if(!text.isBlank())try{switch(field.type()){
                case "date"->LocalDate.parse(text);case "month"->YearMonth.parse(text);
                case "code"->{if(!text.matches("[A-Za-z0-9_.-]{1,50}"))throw new IllegalArgumentException();}
                case "number"->{if(!text.matches("[0-9]{1,3}"))throw new IllegalArgumentException();}
                default->{ }
            }}catch(RuntimeException failure){throw new IllegalArgumentException(field.label()+" 형식을 확인해 주세요.");}
            clean.put(field.id(),text);
        }
        if(!clean.keySet().containsAll(values.keySet()))throw new IllegalArgumentException("허용되지 않은 입력 항목입니다.");return clean;
    }
    private void period(Map<String,String> values,String from,String to){if(!values.get(from).isBlank()&&!values.get(to).isBlank()&&LocalDate.parse(values.get(to)).isBefore(LocalDate.parse(values.get(from))))throw new IllegalArgumentException("완료일이 요청일 또는 시작일보다 빠릅니다.");}
    private List<String> validateFiles(List<String> values,Work old){
        List<String> ids=values==null?List.of():values.stream().distinct().toList();if(ids.size()>10)throw new IllegalArgumentException("각 첨부 구분은 10개 이하여야 합니다.");
        for(String id:ids){var metadata=attachments.metadata(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id));
            if(!metadata.owner().equals(current.require().username())&&(old==null||!linked(old,id)))throw new ApiException(ErrorCode.ACCESS_DENIED);
        }return ids;
    }
    private Definition definition(String id){Definition value=definitions.get(id);if(value==null)throw new IllegalArgumentException("과업·이슈 구분을 확인해 주세요.");return value;}
    private Work require(long id){Work value=works.get(id);if(value==null)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id);return value;}
    private boolean linked(Work work,String id){return work.requestFiles().contains(id)||work.responseFiles().contains(id);}
    @Override public boolean isLinked(String id){return works.values().stream().anyMatch(work->linked(work,id));}
    @Override public boolean canReadAttachment(String id){return canRead()&&works.values().stream().anyMatch(work->!work.deleted()&&linked(work,id));}
    private boolean canRead(){return permissions.isAllowed(current.authentication(),"SYSTEM_AUTH","AUTHORITY","AUTHORITY_READ");}
    private void requireRead(){current.require();if(!canRead())throw new ApiException(ErrorCode.ACCESS_DENIED);}
    private void requireWrite(){current.require();if(!permissions.isAllowed(current.authentication(),"SYSTEM_AUTH","AUTHORITY","AUTHORITY_UPDATE"))throw new ApiException(ErrorCode.ACCESS_DENIED);}
    public record WriteWork(String type,Map<String,String> values,List<String> requestFiles,List<String> responseFiles,long version) { }
    public record Work(long id,String type,long number,Map<String,String> values,List<String> requestFiles,List<String> responseFiles,String createdBy,Instant createdAt,String updatedBy,Instant updatedAt,long version,boolean deleted) { }
    public record Change(long version,String action,String status,String actor,Instant occurredAt) { }
    @Override public String stateKey(){return "operations";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){Map<Long,List<Change>> changes=new TreeMap<>();history.forEach((id,list)->changes.put(id,List.copyOf(list)));return new StoredState(List.copyOf(works.values()),Map.copyOf(numbers),changes,sequence);}
    @Override public void restoreState(Object raw){StoredState state=(StoredState)raw;works.clear();state.works().forEach(w->works.put(w.id(),w));numbers.clear();numbers.putAll(state.numbers());history.clear();state.history().forEach((id,list)->history.put(id,new ArrayList<>(list)));sequence=state.sequence();}
    public record StoredState(List<Work> works,Map<String,Long> numbers,Map<Long,List<Change>> history,long sequence) { }
}
