package com.example.permissiondemo.directory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentStatus;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AssignmentType;
import com.example.permissiondemo.authorization.AuthoritySsoHistoryLinkService;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** 사용자 SSO소속과 업무담당자 조회 범위. 기존 권한 조직 식별자는 변경하지 않는다. */
@Service
@StateBoundary
public class UserSsoOrganizationService implements StateParticipant {
    private final Map<String, Affiliation> affiliations = new LinkedHashMap<>();
    private final DirectoryService directory;
    private final OrganizationMappingService mappings;
    private final CommonCodeService codes;
    private final CurrentUserContext current;
    private final AuditEventService audit;
    private final AuthorizationCatalog catalog;
    private final AuthoritySsoHistoryLinkService historyLinks;
    private ObjectProvider<SsoAuthorityScopeService> scopes;

    @Autowired
    public void configureScopeInvalidation(ObjectProvider<SsoAuthorityScopeService> scopes) { this.scopes = scopes; }

    public UserSsoOrganizationService(DirectoryService directory, OrganizationMappingService mappings,
            CommonCodeService codes, CurrentUserContext current, AuditEventService audit,
            AuthorizationCatalog catalog, AuthoritySsoHistoryLinkService historyLinks) {
        this.directory = directory;
        this.mappings = mappings;
        this.codes = codes;
        this.current = current;
        this.audit = audit;
        this.catalog = catalog;
        this.historyLinks = historyLinks;
    }

    public List<Affiliation> list() {
        return affiliations.values().stream().sorted(Comparator.comparing(Affiliation::username)).toList();
    }

    /** 사용자에게 명시적으로 저장된 현재 SSO 소속을 반환한다. */
    public Optional<Affiliation> find(String username) {
        return Optional.ofNullable(affiliations.get(username));
    }

    /** 원본 CM087/BONBU 판정과 활성 SSO 대응 조건을 그대로 사용해 본부 범위인지 확인한다. */
    public boolean hasHeadquartersScope(String username) {
        Affiliation affiliation = affiliations.get(username);
        return resolve(affiliation, mappingIndex()) != null && isHeadquarters(affiliation.ssoOfficeCode());
    }

    public Affiliation save(WriteAffiliation value) {
        return save(value, false);
    }

    /** Directory bootstrap은 비활성 사용자의 폐지 SSO 소속도 이력값으로 보존한다. */
    public Affiliation saveImported(WriteAffiliation value) {
        return save(value, true);
    }

    private Affiliation save(WriteAffiliation value, boolean preserveInactiveHistory) {
        String username = required(value.username());
        String ssoOffice = required(value.ssoOfficeCode());
        String ssoDepartment = required(value.ssoDepartmentCode());
        var person = directory.person(username);
        var mapping = mappingIndex().get(new SsoKey(ssoOffice, ssoDepartment));
        if (mapping == null || !mapping.active() && !(preserveInactiveHistory && !person.active())) {
            throw new ApiException(ErrorCode.CONFLICT, "사용 중인 SSO 조직 대응을 선택해 주세요.");
        }
        Affiliation old = affiliations.get(username);
        if (value.expectedVersion() != (old == null ? 0 : old.version())) {
            throw new ApiException(ErrorCode.CONFLICT, "SSO소속 정보가 변경되었습니다. 다시 조회해 주세요.");
        }
        Affiliation saved = new Affiliation(username, ssoOffice, ssoDepartment, value.expectedVersion() + 1);
        affiliations.put(username, saved);
        if (scopes != null && (old == null || !old.ssoOfficeCode().equals(saved.ssoOfficeCode()) || !old.ssoDepartmentCode().equals(saved.ssoDepartmentCode()))) {
            var service = scopes.getIfAvailable();
            if (service != null) service.invalidateUser(username);
        }
        audit.record("USER_SSO_ORGANIZATION_SAVED", "USER_SSO_ORGANIZATION", username, "SUCCESS", Map.of());
        return saved;
    }

    /** 관리자 전체 목록과 별도로, 명시적 SSO소속에 따른 사업소 범위만 조회한다. */
    public LookupResult colleagues(String name, String officeCode, String ssoDepartmentName) {
        return colleagues(name, officeCode, ssoDepartmentName, null, null, null);
    }

    public LookupResult colleagues(String name, String officeCode, String ssoDepartmentName,
            String authId, AssignmentStatus procStatus, String systemPrefix) {
        Map<SsoKey, OrganizationMappingService.Mapping> index = mappingIndex();
        Affiliation actor = affiliations.get(current.require().username());
        var actorMapping = resolve(actor, index);
        if (actorMapping == null) throw new ApiException(ErrorCode.ACCESS_DENIED, "사용 가능한 SSO소속을 등록해 주세요.");
        boolean headquarters = isHeadquarters(actor.ssoOfficeCode());
        String requestedOffice = optional(officeCode);
        if ("100".equals(requestedOffice)) requestedOffice = "200";
        String scopeOffice = actorMapping.officeCode();
        if (headquarters) {
            scopeOffice = requestedOffice;
        } else if (scopeOffice.isEmpty() || !requestedOffice.isEmpty() && !scopeOffice.equals(requestedOffice)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "소속 사업소 범위를 확인해 주세요.");
        }

        var local = directory.view();
        Map<String, String> officeNames = new LinkedHashMap<>();
        local.offices().forEach(office -> officeNames.put(office.code(), office.name()));
        int[] namePattern = pattern(name);
        int[] departmentPattern = pattern(ssoDepartmentName);
        int[] systemPattern = systemPrefix == null || systemPrefix.isEmpty() ? null : (systemPrefix + "%").codePoints().toArray();
        List<AuthoritySsoHistoryLinkService.Link> linkedHistory = historyLinks.list();
        List<Colleague> people = new ArrayList<>();
        for (var person : local.people()) {
            Affiliation affiliation = affiliations.get(person.username());
            var mapping = resolve(affiliation, index);
            if (mapping == null || !scopeOffice.isEmpty() && !mapping.officeCode().equals(scopeOffice)
                    || !matches(person.name(), namePattern) || !matches(mapping.ssoDepartmentName(), departmentPattern)
                    || !hasLatestHistory(affiliation, linkedHistory, authId, procStatus, systemPattern)) continue;
            people.add(new Colleague(person.username(), person.name(), mapping.officeCode(),
                    officeNames.getOrDefault(mapping.officeCode(), ""), affiliation.ssoOfficeCode(), affiliation.ssoDepartmentCode(),
                    mapping.ssoDepartmentName(), person.active()));
        }
        people.sort(Comparator.comparing(Colleague::name).thenComparing(Colleague::username));
        return new LookupResult(headquarters, scopeOffice, List.copyOf(people));
    }

    /** 현재 SSO조직 이력의 최신 건을 먼저 결정한 뒤 처리상태를 적용한다. 유효기간·마스터 활성 여부는 조회 조건이 아니다. */
    private boolean hasLatestHistory(Affiliation affiliation, List<AuthoritySsoHistoryLinkService.Link> linkedHistory,
            String authId, AssignmentStatus procStatus, int[] systemPattern) {
        var assignments = catalog.assignmentsFor(affiliation.username());
        Map<String, LinkedAssignment> latest = new LinkedHashMap<>();
        Comparator<AuthoritySsoHistoryLinkService.Link> order = Comparator.comparing(AuthoritySsoHistoryLinkService.Link::registeredAt)
                .thenComparingLong(AuthoritySsoHistoryLinkService.Link::assignmentSequence);
        for (var link : linkedHistory) {
            if (link.assignmentType() != AssignmentType.DIRECT || !link.username().equals(affiliation.username())
                    || !link.ssoOfficeCode().equals(affiliation.ssoOfficeCode()) || !link.ssoDepartmentCode().equals(affiliation.ssoDepartmentCode())
                    || !matches(link.authorityId(), systemPattern)) continue;
            var assignment = assignments.stream().filter(item -> item.type() == AssignmentType.DIRECT
                    && item.organizationId().equals(link.organizationId()) && item.authorityId().equals(link.authorityId())
                    && item.sequence() == link.assignmentSequence()).findFirst().orElse(null);
            if (assignment == null) continue;
            var previous = latest.get(link.authorityId());
            if (previous == null || order.compare(link, previous.link()) > 0) latest.put(link.authorityId(), new LinkedAssignment(link, assignment));
        }
        return latest.values().stream().anyMatch(item -> catalog.findAuthority(item.assignment().authorityId()).isPresent()
                && (authId == null || authId.isEmpty() || authId.equals(item.assignment().authorityId()))
                && (procStatus == null || procStatus == item.assignment().status()));
    }

    private record LinkedAssignment(AuthoritySsoHistoryLinkService.Link link, AuthorizationCatalog.AuthorityAssignment assignment) { }

    private boolean isHeadquarters(String ssoOfficeCode) {
        if (!codes.groupSummary().containsKey("CM087")) return false;
        // 원본은 하위 사용 여부와 하위 코드명만 비교한다. 그룹·상위의 표시 기간은 판정 조건이 아니다.
        return codes.findItems("CM087", false, "BONBU").stream()
                .anyMatch(item -> item.active() && item.name().equals(ssoOfficeCode));
    }

    private Map<SsoKey, OrganizationMappingService.Mapping> mappingIndex() {
        Map<SsoKey, OrganizationMappingService.Mapping> result = new LinkedHashMap<>();
        mappings.list().forEach(mapping -> result.put(new SsoKey(mapping.ssoOfficeCode(), mapping.ssoDepartmentCode()), mapping));
        return result;
    }

    private OrganizationMappingService.Mapping resolve(Affiliation affiliation, Map<SsoKey, OrganizationMappingService.Mapping> index) {
        if (affiliation == null) return null;
        var mapping = index.get(new SsoKey(affiliation.ssoOfficeCode(), affiliation.ssoDepartmentCode()));
        return mapping == null || !mapping.active() ? null : mapping;
    }

    private int[] pattern(String value) { return value == null || value.isEmpty() ? null : ("%" + value + "%").codePoints().toArray(); }

    /** Oracle LIKE의 %·_와 대소문자 구분을 유지한다. */
    private boolean matches(String value, int[] pattern) {
        if (pattern == null) return true;
        int[] letters = value.codePoints().toArray();
        boolean[] previous = new boolean[letters.length + 1];
        previous[0] = true;
        for (int token : pattern) {
            boolean[] next = new boolean[letters.length + 1];
            next[0] = token == '%' && previous[0];
            for (int i = 1; i <= letters.length; i++) {
                next[i] = token == '%' ? previous[i] || next[i - 1] : previous[i - 1] && (token == '_' || token == letters[i - 1]);
            }
            previous = next;
        }
        return previous[letters.length];
    }

    private String optional(String value) { return value == null ? "" : value.trim(); }
    private String required(String value) {
        String normalized = optional(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException("사용자와 SSO사업소·부서 코드를 입력해 주세요.");
        return normalized;
    }

    private record SsoKey(String officeCode, String departmentCode) { }
    public record Affiliation(String username, String ssoOfficeCode, String ssoDepartmentCode, long version) { }
    public record WriteAffiliation(String username, String ssoOfficeCode, String ssoDepartmentCode, long expectedVersion) { }
    public record StoredState(List<Affiliation> affiliations) { }
    public record Colleague(String username, String name, String officeCode, String officeName, String ssoOfficeCode,
            String ssoDepartmentCode, String ssoDepartmentName, boolean active) { }
    public record LookupResult(boolean headquarters, String officeCode, List<Colleague> people) { }

    @Override public String stateKey() { return "user-sso-organizations"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(list()); }
    @Override public void restoreState(Object raw) {
        affiliations.clear();
        if (raw == null || ((StoredState) raw).affiliations() == null) return;
        ((StoredState) raw).affiliations().forEach(item -> affiliations.put(item.username(), item));
    }
}
