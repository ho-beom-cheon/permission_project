package com.example.permissiondemo.directory;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 사용자·사업소·부서·담당지역·담당업무 관리. 원본 VO의 업무 관계를 별도 로컬 저장소에 보관한다. */
@Service
@StateBoundary
public class DirectoryService implements StateParticipant {
    private final AuthorizationCatalog catalog;
    private final AuditEventService audit;
    private final Map<String, Office> offices = new LinkedHashMap<>();
    private final Map<String, Department> departments = new LinkedHashMap<>();
    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<String, Person> people = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public DirectoryService(AuthorizationCatalog catalog, AuditEventService audit) {
        this.catalog = catalog; this.audit = audit;
        // 기존 샘플의 가상 조직만 초기 등록한다. 운영 사용자·조직을 읽지 않는다.
        for (var user : catalog.users()) {
            offices.putIfAbsent(user.organizationId(), new Office(user.organizationId(), "가상 " + user.organizationId(), "", "", "", true));
            people.put(user.username(), new Person(user.username(), user.username(), user.organizationId(), "", "", "", "", "", "", user.active(), Set.of()));
        }
    }

    public DirectoryView view() {
        return new DirectoryView(List.copyOf(offices.values()), List.copyOf(departments.values()),
                List.copyOf(regions.values()), people.values().stream().sorted(Comparator.comparing(Person::username)).toList(), List.copyOf(jobs.values()));
    }

    public Person person(String username) {
        Person result = people.get(username);
        if (result == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username);
        return result;
    }

    public Office saveOffice(Office value) {
        requireCode(value.code()); requireName(value.name());
        text(value.abbreviation(),100); text(value.address(),500); text(value.telephone(),30);
        if (!value.active() && (people.values().stream().anyMatch(p -> p.active() && p.officeCode().equals(value.code()))
                || departments.values().stream().anyMatch(d -> d.active() && d.officeCode().equals(value.code()))
                || regions.values().stream().anyMatch(r -> r.active() && r.officeCode().equals(value.code())))) {
            throw new ApiException(ErrorCode.CONFLICT, "사용 중인 사업소입니다.");
        }
        offices.put(value.code(), value); changed("OFFICE", value.code()); return value;
    }

    public Department saveDepartment(Department value) {
        requireCode(value.code()); requireName(value.name()); requireOffice(value.officeCode());
        text(value.description(),2000);
        if (!value.active() && people.values().stream().anyMatch(p -> p.active() && p.officeCode().equals(value.officeCode()) && p.departmentCode().equals(value.code()))) {
            throw new ApiException(ErrorCode.CONFLICT, "사용 중인 부서입니다.");
        }
        departments.put(value.officeCode() + ":" + value.code(), value); changed("DEPARTMENT", value.officeCode() + ":" + value.code()); return value;
    }

    public Region saveRegion(Region value) {
        requireCode(value.code()); requireName(value.name()); requireOffice(value.officeCode());
        text(value.districtCode(),50); text(value.districtName(),100);
        Region old = regions.get(value.code());
        if (old != null && (!value.active() || !old.officeCode().equals(value.officeCode()))
                && people.values().stream().anyMatch(p -> p.regionIds().contains(value.code()))) {
            throw new ApiException(ErrorCode.CONFLICT, "사용자에게 배정된 지역입니다.");
        }
        regions.put(value.code(), value); changed("REGION", value.code()); return value;
    }

    public Person savePerson(Person value) {
        if (value.username() == null || !value.username().matches("[A-Za-z0-9_]{1,50}")) throw new IllegalArgumentException("사용자 ID 형식을 확인해 주세요.");
        requireName(value.name()); requireOffice(value.officeCode());
        String dept = value.departmentCode() == null ? "" : value.departmentCode();
        if (!dept.isBlank()) {
            Department department = departments.get(value.officeCode() + ":" + dept);
            if (department == null || !department.active()) throw new ApiException(ErrorCode.CONFLICT, "사용 가능한 부서가 아닙니다.");
        }
        Set<String> assigned = value.regionIds() == null ? Set.of() : Set.copyOf(value.regionIds());
        if (assigned.size() > 500) throw new IllegalArgumentException("담당지역은 500개 이하여야 합니다.");
        assigned.forEach(id -> {
            Region region = regions.get(id);
            if (region == null || !region.active() || !region.officeCode().equals(value.officeCode())) throw new ApiException(ErrorCode.CONFLICT, "소속 사업소의 지역만 배정할 수 있습니다.");
        });
        Person saved = new Person(value.username(), value.name(), value.officeCode(), dept, text(value.rankCode(), 50),
                text(value.telephone(), 30), text(value.mobile(), 30), text(value.email(), 150), text(value.jobDescription(), 1000), value.active(), assigned);
        catalog.saveUserProfile(value.username(), dept.isBlank() ? value.officeCode() : value.officeCode() + ":" + dept, value.active());
        people.put(value.username(), saved); changed("USER", value.username()); return saved;
    }

    public Job saveJob(Job value) {
        requireCode(value.id()); requireCode(value.jobCode()); requireName(value.name());
        Person person = person(value.username());
        if (!person.active() || value.validFrom() == null || value.validTo() == null || value.validTo().isBefore(value.validFrom())) {
            throw new IllegalArgumentException("사용자 상태와 담당업무 기간을 확인해 주세요.");
        }
        jobs.put(value.id(), value); changed("USER_JOB", value.id()); return value;
    }

    public void deleteJob(String id) {
        if (jobs.remove(id) == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id);
        audit.record("USER_JOB_DELETED", "USER_JOB", id, "SUCCESS", Map.of());
    }

    private void requireOffice(String code) {
        Office office = offices.get(code);
        if (office == null || !office.active()) throw new ApiException(ErrorCode.CONFLICT, "사용 가능한 사업소가 아닙니다.");
    }
    private void requireCode(String value) { if (value == null || !value.matches("[A-Za-z0-9_]{1,50}")) throw new IllegalArgumentException("코드는 영문·숫자·밑줄 1~50자입니다."); }
    private void requireName(String value) { if (value == null || value.isBlank() || value.length() > 100) throw new IllegalArgumentException("이름은 1~100자입니다."); }
    private String text(String value, int max) { if (value != null && value.length() > max) throw new IllegalArgumentException("입력값의 길이를 확인해 주세요."); return value == null ? "" : value.trim(); }
    private void changed(String type, String id) { audit.record(type + "_SAVED", type, id, "SUCCESS", Map.of()); }

    public record Office(String code, String name, String abbreviation, String address, String telephone, boolean active) { }
    public record Department(String code, String officeCode, String name, String description, boolean active) { }
    public record Region(String code, String officeCode, String districtCode, String districtName, String name, boolean active) { }
    public record Person(String username, String name, String officeCode, String departmentCode, String rankCode,
            String telephone, String mobile, String email, String jobDescription, boolean active, Set<String> regionIds) { }
    public record Job(String id, String username, String jobCode, String name, LocalDate validFrom, LocalDate validTo, boolean active) { }
    public record DirectoryView(List<Office> offices, List<Department> departments, List<Region> regions, List<Person> people, List<Job> jobs) { }
    @Override public String stateKey() { return "directory"; }
    @Override public Class<?> stateType() { return DirectoryView.class; }
    @Override public Object snapshotState() { return view(); }
    @Override public void restoreState(Object raw) {
        DirectoryView state = (DirectoryView) raw;
        offices.clear(); state.offices().forEach(item -> offices.put(item.code(), item));
        departments.clear(); state.departments().forEach(item -> departments.put(item.officeCode() + ":" + item.code(), item));
        regions.clear(); state.regions().forEach(item -> regions.put(item.code(), item));
        people.clear(); state.people().forEach(item -> people.put(item.username(), item));
        jobs.clear(); state.jobs().forEach(item -> jobs.put(item.id(), item));
    }
}
