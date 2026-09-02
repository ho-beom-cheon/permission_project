package com.example.permissiondemo.directory;

import java.time.*;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 개인 일정의 소유자는 인증 사용자로 고정한다. 조회·수정·삭제 모두 같은 소유자 조건을 적용한다. */
@Service
@StateBoundary
public class PersonalScheduleService implements StateParticipant {
    private final Map<Long, Schedule> schedules = new TreeMap<>();
    private final CurrentUserContext current;
    private final AuditEventService audit;
    private final Clock clock;
    private long sequence;
    public PersonalScheduleService(CurrentUserContext current, AuditEventService audit, Clock clock) { this.current=current; this.audit=audit; this.clock=clock; }
    public List<Schedule> list(LocalDate from, LocalDate to) {
        if (from==null || to==null || to.isBefore(from) || to.isAfter(from.plusYears(1))) throw new IllegalArgumentException("조회 기간은 1년 이내로 입력해 주세요.");
        String actor=current.require().username();
        return schedules.values().stream().filter(s->s.username().equals(actor)&&!s.end().toLocalDate().isBefore(from)&&!s.start().toLocalDate().isAfter(to))
                .sorted(Comparator.comparing(Schedule::start).thenComparingLong(Schedule::id)).toList();
    }
    public Schedule save(long id, WriteSchedule value) {
        String actor=current.require().username();Schedule old=id==0?null:owned(id);
        if((old==null?0:old.version())!=value.version())throw new ApiException(ErrorCode.CONFLICT,id);
        if(value.name()==null||value.name().isBlank()||value.name().length()>200||value.body()!=null&&value.body().length()>10000
                ||value.category()==null||!value.category().matches("[A-Za-z0-9_]{1,30}")||value.status()==null
                ||value.start()==null||value.end()==null||value.end().isBefore(value.start()))throw new IllegalArgumentException("일정의 제목·분류·상태·기간을 확인해 주세요.");
        long key=old==null?++sequence:id;Instant now=Instant.now(clock);
        Schedule saved=new Schedule(key,actor,value.name().trim(),Objects.toString(value.body(),""),value.category(),value.status(),value.start(),value.end(),value.version()+1,old==null?now:old.createdAt(),now);
        schedules.put(key,saved);audit.record("PERSONAL_SCHEDULE_SAVED","SCHEDULE",String.valueOf(key),"SUCCESS",Map.of());return saved;
    }
    public void delete(long id,long version){Schedule old=owned(id);if(old.version()!=version)throw new ApiException(ErrorCode.CONFLICT,id);schedules.remove(id);audit.record("PERSONAL_SCHEDULE_DELETED","SCHEDULE",String.valueOf(id),"SUCCESS",Map.of());}
    private Schedule owned(long id){Schedule value=schedules.get(id);if(value==null)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id);if(!value.username().equals(current.require().username()))throw new ApiException(ErrorCode.ACCESS_DENIED);return value;}
    public enum Status { OPEN, DONE, CANCELLED }
    public record WriteSchedule(String name,String body,String category,Status status,LocalDateTime start,LocalDateTime end,long version) { }
    public record Schedule(long id,String username,String name,String body,String category,Status status,LocalDateTime start,LocalDateTime end,long version,Instant createdAt,Instant updatedAt) { }
    @Override public String stateKey(){return "personal-schedules";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){return new StoredState(List.copyOf(schedules.values()),sequence);}
    @Override public void restoreState(Object raw){StoredState state=(StoredState)raw;schedules.clear();state.schedules().forEach(s->schedules.put(s.id(),s));sequence=state.sequence();}
    public record StoredState(List<Schedule> schedules,long sequence) { }
}
