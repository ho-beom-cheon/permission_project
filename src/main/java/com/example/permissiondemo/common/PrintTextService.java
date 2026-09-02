package com.example.permissiondemo.common;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 사업소·업무·상세업무 묶음 안의 순번과 출력문구를 함께 저장한다. */
@Service
@StateBoundary
public class PrintTextService implements StateParticipant {
    private final Map<String, Group> groups = new TreeMap<>();
    private final DirectoryService directory;
    private final CommonCodeService codes;
    private final CurrentUserContext current;
    private final AuditEventService audit;
    private final Clock clock;

    public PrintTextService(DirectoryService directory, CommonCodeService codes, CurrentUserContext current, AuditEventService audit, Clock clock) {
        this.directory=directory; this.codes=codes; this.current=current; this.audit=audit; this.clock=clock;
    }
    public Options options() {
        return new Options(directory.view().offices().stream().filter(DirectoryService.Office::active).toList(), activeCodes("CM013"), activeCodes("CM040"));
    }
    private List<CommonCodeService.CommonCodeItem> activeCodes(String group) {
        return codes.definitions().stream().anyMatch(g->g.code().equals(group))
                ? codes.findActiveItems(group).stream().filter(c->c.parentCode()==null).toList() : List.of();
    }
    public PageResult<Group> list(String officeCd, String jobSeCd, PageQuery page) {
        return PageResult.of(groups.values().stream().filter(g->!g.deleted())
                .filter(g->officeCd==null||officeCd.isBlank()||g.officeCd().equals(officeCd))
                .filter(g->jobSeCd==null||jobSeCd.isBlank()||g.jobSeCd().equals(jobSeCd)).toList(),page);
    }
    public Group get(String id) {
        Group group=groups.get(id);
        if(group==null||group.deleted())throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"출력문구를 찾을 수 없습니다.");
        return group;
    }
    public Group save(WriteGroup value) {
        if(value==null)throw new IllegalArgumentException("출력문구가 필요합니다.");
        String office=code(value.officeCd()), job=code(value.jobSeCd()), detail=code(value.jobSeDetailCd());
        String id=office+"|"+job+"|"+detail; Group previous=groups.get(id);
        // 삭제 후 재등록도 이전 버전보다 큰 번호를 사용해 오래된 요청의 재사용을 막는다.
        boolean creating=previous==null||previous.deleted();
        if(value.version()!=(creating?0:previous.version()))throw new ApiException(ErrorCode.CONFLICT,"출력문구가 변경됐습니다. 다시 조회해 주세요.");
        Options options=options();
        if(options.offices().stream().noneMatch(o->o.code().equals(office))
                ||options.jobs().stream().noneMatch(c->c.code().equals(job))
                ||options.details().stream().noneMatch(c->c.code().equals(detail))
                ||detail.length()<2||!detail.substring(0,2).equals(job)) {
            throw new ApiException(ErrorCode.CONFLICT,"사용 가능한 사업소와 CM013 업무, 해당 업무의 CM040 상세코드를 먼저 등록해 주세요.");
        }
        if(value.lines()==null||value.lines().isEmpty()||value.lines().size()>200)throw new IllegalArgumentException("문구 행은 1~200개여야 합니다.");
        Set<Integer> sequences=new HashSet<>(); List<Line> lines=new ArrayList<>();
        for(Line line:value.lines()) {
            if(line==null||line.seq()<1||!sequences.add(line.seq()))throw new IllegalArgumentException("문구 순번은 중복 없는 양수여야 합니다.");
            String text=Objects.toString(line.cn(),"");
            if(text.length()>100)throw new IllegalArgumentException("출력문구는 행마다 100자 이하여야 합니다.");
            lines.add(new Line(line.seq(),text));
        }
        lines.sort(Comparator.comparingInt(Line::seq));
        Instant now=Instant.now(clock); String actor=current.require().username();
        Group saved=new Group(id,office,job,detail,List.copyOf(lines),lines.stream().filter(l->!l.cn().isEmpty()).count(),
                previous==null?1:previous.version()+1,creating?actor:previous.createdBy(),creating?now:previous.createdAt(),actor,now,false);
        groups.put(id,saved); audit.record("PRINT_TEXT_SAVED","PRINT_TEXT",id,"SUCCESS",Map.of("version",saved.version(),"lines",lines.size()));
        return saved;
    }
    public void delete(String id,long version) {
        Group previous=get(id);
        if(version!=previous.version())throw new ApiException(ErrorCode.CONFLICT,"출력문구가 변경됐습니다. 다시 조회해 주세요.");
        groups.put(id,new Group(id,previous.officeCd(),previous.jobSeCd(),previous.jobSeDetailCd(),List.of(),0,version+1,
                previous.createdBy(),previous.createdAt(),current.require().username(),Instant.now(clock),true));
        audit.record("PRINT_TEXT_DELETED","PRINT_TEXT",id,"SUCCESS",Map.of("version",version+1));
    }
    private String code(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_.-]{1,50}"))throw new IllegalArgumentException("사업소·업무·상세업무 코드를 선택해 주세요.");
        return value;
    }
    public record Line(int seq,String cn) { }
    public record WriteGroup(String officeCd,String jobSeCd,String jobSeDetailCd,List<Line> lines,long version) { }
    public record Group(String id,String officeCd,String jobSeCd,String jobSeDetailCd,List<Line> lines,long contentCount,long version,
                        String createdBy,Instant createdAt,String updatedBy,Instant updatedAt,boolean deleted) { }
    public record Options(List<DirectoryService.Office> offices,List<CommonCodeService.CommonCodeItem> jobs,List<CommonCodeService.CommonCodeItem> details) { }
    public record StoredState(Map<String,Group> groups) { }
    @Override public String stateKey(){return "print-text";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){return new StoredState(groups);}
    @Override public void restoreState(Object raw){groups.clear();groups.putAll(((StoredState)raw).groups());}
}
