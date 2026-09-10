package com.example.permissiondemo.directory;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    private static final Pattern TELEPHONE = Pattern.compile("[0-9]{1,15}");
    private static final Pattern MOBILE = Pattern.compile("010[0-9]{8}");
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
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

    /** 권한 신청 화면에 필요한 편집값과 현재 사업소의 사용 가능한 담당지역만 반환한다. */
    public ApplicantView applicant(String username) {
        Person person = person(username);
        List<Region> available = regions.values().stream()
                .filter(region -> region.active() && regionAllowed(person.officeCode(), region))
                .sorted(Comparator.comparing(Region::code))
                .toList();
        return new ApplicantView(person.rankCode(), person.telephone(), person.mobile(), person.email(),
                person.jobDescription(), Set.copyOf(person.regionIds()), available);
    }

    /** 신청자가 수정할 수 있는 연락처·담당업무·지역만 바꾸고 신원과 소속은 그대로 보존한다. */
    public Person updateApplicant(String username, ApplicantUpdate update) {
        if (update == null) throw new IllegalArgumentException("신청자 정보가 필요합니다.");
        Person old = person(username);
        required(update.rankCode(), 50, "직급");
        required(update.telephone(), 15, "부서전화");
        if (!TELEPHONE.matcher(update.telephone()).matches()) {
            throw new IllegalArgumentException("부서전화는 숫자 1~15자리여야 합니다.");
        }
        String mobile = update.mobile() == null ? "" : update.mobile();
        if (!mobile.isEmpty() && !MOBILE.matcher(mobile).matches()) {
            throw new IllegalArgumentException("휴대전화는 010으로 시작하는 11자리 숫자여야 합니다.");
        }
        required(update.email(), 150, "이메일");
        if (!EMAIL.matcher(update.email()).matches()) {
            throw new IllegalArgumentException("이메일 형식을 확인해 주세요.");
        }
        required(update.jobDescription(), 1000, "담당업무");
        if (update.regionIds() == null || update.regionIds().size() > 500) {
            throw new IllegalArgumentException("담당지역은 500개 이하여야 합니다.");
        }
        if (update.regionIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_]{1,50}"))) {
            throw new IllegalArgumentException("담당지역 코드를 확인해 주세요.");
        }
        Set<String> assigned = Set.copyOf(update.regionIds());
        assigned.forEach(id -> {
            Region region = regions.get(id);
            if (region == null || !region.active() || !regionAllowed(old.officeCode(), region)) {
                throw new ApiException(ErrorCode.CONFLICT, "소속 사업소의 사용 가능한 지역만 배정할 수 있습니다.");
            }
        });
        Person saved = new Person(old.username(), old.name(), old.officeCode(), old.departmentCode(),
                update.rankCode(), update.telephone(), mobile, update.email().toLowerCase(Locale.ROOT),
                update.jobDescription(), old.active(), assigned);
        people.put(username, saved);
        changed("USER_APPLICANT", username);
        return saved;
    }

    /** 원본 searchUsr의 사용자명·사업소·사용 여부 조건을 조합한다. */
    public List<Person> searchPeople(String name, String officeCode, Boolean active) {
        int[] namePattern = name == null || name.isEmpty() ? null : ("%" + name + "%").codePoints().toArray();
        return people.values().stream()
                .filter(person -> namePattern == null || matchesName(person.name(), namePattern))
                .filter(person -> officeCode == null || officeCode.isEmpty() || person.officeCode().equals(officeCode))
                .filter(person -> active == null || person.active() == active)
                .sorted(Comparator.comparing(Person::username))
                .toList();
    }

    /** SQL LIKE의 %·_만 와일드카드로 해석하며, 대소문자와 나머지 문자는 그대로 비교한다. */
    private boolean matchesName(String name, int[] pattern) {
        int[] letters = name.codePoints().toArray();
        boolean[] previous = new boolean[letters.length + 1];
        previous[0] = true;
        for (int token : pattern) {
            boolean[] current = new boolean[letters.length + 1];
            current[0] = token == '%' && previous[0];
            for (int index = 1; index <= letters.length; index++) {
                current[index] = token == '%' ? previous[index] || current[index - 1]
                        : previous[index - 1] && (token == '_' || token == letters[index - 1]);
            }
            previous = current;
        }
        return previous[letters.length];
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
            if (region == null || !region.active() || !regionAllowed(value.officeCode(), region)) throw new ApiException(ErrorCode.CONFLICT, "소속 사업소의 지역만 배정할 수 있습니다.");
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
    /** 원본 담당지역 조회에서 002 사업소는 사업소 조건 없이 전체 활성 지역을 사용한다. */
    private boolean regionAllowed(String officeCode, Region region) {
        return "002".equals(officeCode) || region.officeCode().equals(officeCode);
    }
    private void requireCode(String value) { if (value == null || !value.matches("[A-Za-z0-9_]{1,50}")) throw new IllegalArgumentException("코드는 영문·숫자·밑줄 1~50자입니다."); }
    private void requireName(String value) { if (value == null || value.isBlank() || value.length() > 100) throw new IllegalArgumentException("이름은 1~100자입니다."); }
    private void required(String value, int max, String fieldName) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(fieldName + "을(를) 확인해 주세요.");
        }
    }
    private String text(String value, int max) { if (value != null && value.length() > max) throw new IllegalArgumentException("입력값의 길이를 확인해 주세요."); return value == null ? "" : value.trim(); }
    private void changed(String type, String id) { audit.record(type + "_SAVED", type, id, "SUCCESS", Map.of()); }

    public record Office(String code, String name, String abbreviation, String address, String telephone, boolean active) { }
    public record Department(String code, String officeCode, String name, String description, boolean active) { }
    public record Region(String code, String officeCode, String districtCode, String districtName, String name, boolean active) { }
    public record Person(String username, String name, String officeCode, String departmentCode, String rankCode,
            String telephone, String mobile, String email, String jobDescription, boolean active, Set<String> regionIds) { }
    public record ApplicantUpdate(String rankCode, String telephone, String mobile, String email,
            String jobDescription, Set<String> regionIds) { }
    public record ApplicantView(String rankCode, String telephone, String mobile, String email,
            String jobDescription, Set<String> regionIds, List<Region> availableRegions) { }
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
