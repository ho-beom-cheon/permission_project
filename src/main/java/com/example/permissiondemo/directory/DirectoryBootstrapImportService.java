package com.example.permissiondemo.directory;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 승인된 INFO 추출본으로 운영 Directory 기준정보를 한 경계에서 적재한다. */
@Service
@StateBoundary
public class DirectoryBootstrapImportService {
    private static final String RANK_GROUP = "CM008";
    private static final LocalDate LONG_AGO = LocalDate.of(2000, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);

    private final DirectoryService directory;
    private final OrganizationMappingService mappings;
    private final UserSsoOrganizationService affiliations;
    private final CommonCodeService codes;
    private final AuthorizationCatalog catalog;
    private final AuditEventService audit;

    public DirectoryBootstrapImportService(DirectoryService directory, OrganizationMappingService mappings,
            UserSsoOrganizationService affiliations, CommonCodeService codes,
            AuthorizationCatalog catalog, AuditEventService audit) {
        this.directory = directory;
        this.mappings = mappings;
        this.affiliations = affiliations;
        this.codes = codes;
        this.catalog = catalog;
        this.audit = audit;
    }

    public ImportResult importRows(ImportRequest raw) {
        ImportRequest request = raw == null ? ImportRequest.empty() : raw.normalized();
        validateRows(request);
        var current = directory.view();
        Map<String, DirectoryService.Office> offices = indexOffices(current.offices());
        Map<String, DirectoryService.Department> departments = indexDepartments(current.departments());
        Map<String, DirectoryService.Region> regions = indexRegions(current.regions());
        Map<String, DirectoryService.Person> people = indexPeople(current.people());

        Set<String> officeKeys = unique(request.offices(), OfficeRow::code, "사업소");
        Set<String> departmentKeys = unique(request.departments(), row -> key(row.officeCode(), row.code()), "부서");
        Set<String> mappingKeys = unique(request.organizationMappings(), row -> key(row.ssoOfficeCode(), row.ssoDepartmentCode()), "SSO 조직 대응");
        Set<String> regionKeys = unique(request.regions(), RegionRow::code, "지역");
        Set<String> personKeys = unique(request.people(), PersonRow::username, "사용자");
        unique(request.userRegions(), row -> key(row.username(), row.regionCode()), "사용자 담당지역");
        unique(request.rankCodes(), RankCodeRow::code, "직급 코드");

        request.offices().forEach(row -> offices.put(row.code(), new DirectoryService.Office(row.code(), row.name(),
                text(row.abbreviation()), text(row.address()), text(row.telephone()), active(row.useFlag()))));
        request.departments().forEach(row -> departments.put(key(row.officeCode(), row.code()),
                new DirectoryService.Department(row.code(), row.officeCode(), row.name(), text(row.description()), active(row.useFlag()))));
        request.regions().forEach(row -> regions.put(row.code(), new DirectoryService.Region(row.code(), row.officeCode(),
                text(row.districtCode()), text(row.districtName()), row.name(), active(row.useFlag()))));

        Map<String, Set<String>> importedRegions = new LinkedHashMap<>();
        personKeys.forEach(username -> importedRegions.put(username, new LinkedHashSet<>()));
        request.userRegions().forEach(row -> {
            if (!personKeys.contains(row.username())) throw conflict("담당지역 사용자는 같은 추출본의 people에 있어야 합니다: " + row.username());
            importedRegions.get(row.username()).add(row.regionCode());
        });
        request.people().forEach(row -> people.put(row.username(), new DirectoryService.Person(row.username(), row.name(),
                row.officeCode(), text(row.departmentCode()), text(row.rankCode()), text(row.telephone()), text(row.mobile()),
                text(row.email()), text(row.jobDescription()), active(row.useFlag()), Set.copyOf(importedRegions.get(row.username())))));
        importedRegions.forEach((username, assigned) -> {
            if (assigned.size() > 500) throw new IllegalArgumentException("담당지역은 사용자별 500개 이하여야 합니다: " + username);
        });

        validateDirectory(offices, departments, regions, people);
        Map<String, OrganizationMappingService.Mapping> finalMappings = finalMappings(request.organizationMappings());
        validateMappings(finalMappings, offices, departments);
        validateAffiliations(request.people(), finalMappings, people);
        validateRanks(request.rankGroup(), request.rankCodes(), people);

        DirectoryService.DirectoryView next = new DirectoryService.DirectoryView(List.copyOf(offices.values()),
                List.copyOf(departments.values()), List.copyOf(regions.values()), List.copyOf(people.values()), current.jobs());
        boolean directoryChanged = !next.equals(current);
        if (directoryChanged) {
            directory.restoreState(next);
            for (String username : personKeys) {
                DirectoryService.Person old = current.people().stream().filter(item -> item.username().equals(username)).findFirst().orElse(null);
                DirectoryService.Person value = people.get(username);
                String organizationId = value.departmentCode().isBlank() ? value.officeCode() : value.officeCode() + ":" + value.departmentCode();
                var oldProfile = catalog.findUser(username).orElse(null);
                if (oldProfile == null || !organizationId.equals(oldProfile.organizationId()) || oldProfile.active() != value.active()) {
                    catalog.saveUserProfile(username, organizationId, value.active());
                }
            }
        }

        int rankChanges = applyRanks(request.rankGroup(), request.rankCodes());
        int mappingChanges = applyMappings(request.organizationMappings());
        int affiliationChanges = applyAffiliations(request.people());
        int directoryChanges = differences(current, offices, departments, regions, people, officeKeys, departmentKeys, regionKeys, personKeys);
        int changed = directoryChanges
                + rankChanges + mappingChanges + affiliationChanges;
        if (changed > 0) audit.record("DIRECTORY_BOOTSTRAP_IMPORTED", "DIRECTORY", "INFO", "SUCCESS",
                Map.of("changed", changed, "people", request.people().size(), "regions", request.regions().size()));
        return new ImportResult(request.offices().size(), request.departments().size(), request.organizationMappings().size(),
                request.regions().size(), request.people().size(), request.userRegions().size(), request.rankCodes().size(), changed);
    }

    private int applyRanks(RankGroupRow group, List<RankCodeRow> rows) {
        if (group == null && rows.isEmpty()) return 0;
        int changed = 0;
        if (!codes.groupSummary().containsKey(RANK_GROUP)) {
            if (group == null) throw conflict("CM008 그룹 정보가 필요합니다.");
            codes.saveGroup(new CommonCodeService.CodeGroup(RANK_GROUP, group.name(), text(group.description()), active(group.useFlag())), 0);
            changed++;
        } else if (group != null) {
            var old = codes.definitions().stream().filter(item -> item.code().equals(RANK_GROUP)).findFirst().orElseThrow();
            var value = new CommonCodeService.CodeGroup(RANK_GROUP, group.name(), text(group.description()), active(group.useFlag()));
            if (!old.equals(value)) { codes.saveGroup(value, codes.groupVersions().get(RANK_GROUP)); changed++; }
        }
        Map<String, CommonCodeService.CommonCodeItem> oldItems = new LinkedHashMap<>();
        codes.findItems(RANK_GROUP, false, null).forEach(item -> oldItems.put(item.code(), item));
        for (RankCodeRow row : rows) {
            var details = new CommonCodeService.DisplayDetails(text(row.description()), text(row.substituteName()),
                    text(row.screenDescription()), active(row.screen1()), active(row.screen2()), active(row.screen3()), active(row.screen4()), active(row.screen5()));
            var value = new CommonCodeService.CommonCodeItem(row.code(), row.name(), null, row.sortOrder(), active(row.useFlag()),
                    LONG_AGO, FAR_FUTURE, details);
            if (!value.equals(oldItems.get(row.code()))) { codes.saveRich(RANK_GROUP, value, codes.groupVersions().get(RANK_GROUP)); changed++; }
        }
        return changed;
    }

    private int applyMappings(List<MappingRow> rows) {
        Map<String, OrganizationMappingService.Mapping> old = new LinkedHashMap<>();
        mappings.list().forEach(item -> old.put(key(item.ssoOfficeCode(), item.ssoDepartmentCode()), item));
        int changed = 0;
        for (MappingRow row : rows) {
            var previous = old.get(key(row.ssoOfficeCode(), row.ssoDepartmentCode()));
            boolean enabled = active(row.useFlag());
            if (previous != null && previous.ssoOfficeName().equals(text(row.ssoOfficeName()))
                    && previous.ssoDepartmentName().equals(text(row.ssoDepartmentName()))
                    && previous.officeCode().equals(text(row.officeCode())) && previous.departmentCode().equals(text(row.departmentCode()))
                    && previous.active() == enabled) continue;
            mappings.save(new OrganizationMappingService.WriteMapping(row.ssoOfficeCode(), row.ssoDepartmentCode(),
                    row.ssoOfficeName(), row.ssoDepartmentName(), row.officeCode(), row.departmentCode(), enabled,
                    previous == null ? 0 : previous.version()));
            changed++;
        }
        return changed;
    }

    private int applyAffiliations(List<PersonRow> rows) {
        Map<String, UserSsoOrganizationService.Affiliation> old = new LinkedHashMap<>();
        affiliations.list().forEach(item -> old.put(item.username(), item));
        int changed = 0;
        for (PersonRow row : rows) {
            var previous = old.get(row.username());
            String ssoOffice = text(row.ssoOfficeCode()), ssoDepartment = text(row.ssoDepartmentCode());
            if (previous != null && previous.ssoOfficeCode().equals(ssoOffice)
                    && previous.ssoDepartmentCode().equals(ssoDepartment)) continue;
            affiliations.saveImported(new UserSsoOrganizationService.WriteAffiliation(row.username(), ssoOffice,
                    ssoDepartment, previous == null ? 0 : previous.version()));
            changed++;
        }
        return changed;
    }

    private void validateDirectory(Map<String, DirectoryService.Office> offices,
            Map<String, DirectoryService.Department> departments, Map<String, DirectoryService.Region> regions,
            Map<String, DirectoryService.Person> people) {
        for (var department : departments.values()) {
            if (!offices.containsKey(department.officeCode())) throw conflict("부서의 사업소가 없습니다: " + department.officeCode());
        }
        for (var region : regions.values()) {
            if (!offices.containsKey(region.officeCode())) throw conflict("지역의 사업소가 없습니다: " + region.code());
        }
        for (var person : people.values()) {
            var office = offices.get(person.officeCode());
            if (office == null || person.active() && !office.active()) throw conflict("사용자의 활성 사업소가 없습니다: " + person.username());
            if (person.active() && !person.departmentCode().isBlank()) {
                var department = departments.get(key(person.officeCode(), person.departmentCode()));
                if (department == null || !department.active()) throw conflict("사용자의 활성 부서가 없습니다: " + person.username());
            }
            for (String regionCode : person.regionIds()) {
                var region = regions.get(regionCode);
                if (region == null || person.active() && (!region.active()
                        || !("002".equals(person.officeCode()) || region.officeCode().equals(person.officeCode())))) {
                    throw conflict("사용자의 담당지역 범위가 올바르지 않습니다: " + person.username() + "/" + regionCode);
                }
            }
        }
        for (var office : offices.values()) {
            if (!office.active() && (people.values().stream().anyMatch(p -> p.active() && p.officeCode().equals(office.code()))
                    || departments.values().stream().anyMatch(d -> d.active() && d.officeCode().equals(office.code()))
                    || regions.values().stream().anyMatch(r -> r.active() && r.officeCode().equals(office.code())))) {
                throw conflict("사용 중인 사업소를 미사용으로 이관할 수 없습니다: " + office.code());
            }
        }
    }

    private Map<String, OrganizationMappingService.Mapping> finalMappings(List<MappingRow> rows) {
        Map<String, OrganizationMappingService.Mapping> result = new LinkedHashMap<>();
        mappings.list().forEach(item -> result.put(key(item.ssoOfficeCode(), item.ssoDepartmentCode()), item));
        for (MappingRow row : rows) {
            var old = result.get(key(row.ssoOfficeCode(), row.ssoDepartmentCode()));
            result.put(key(row.ssoOfficeCode(), row.ssoDepartmentCode()), new OrganizationMappingService.Mapping(
                    row.ssoOfficeCode(), row.ssoDepartmentCode(), text(row.ssoOfficeName()), text(row.ssoDepartmentName()),
                    text(row.officeCode()), text(row.departmentCode()), active(row.useFlag()), old == null ? 1 : old.version() + 1));
        }
        return result;
    }

    private void validateMappings(Map<String, OrganizationMappingService.Mapping> rows, Map<String, DirectoryService.Office> offices,
            Map<String, DirectoryService.Department> departments) {
        for (var row : rows.values()) {
            if (!row.active()) continue;
            String office = text(row.officeCode()), department = text(row.departmentCode());
            if (office.isBlank() || !offices.containsKey(office) || !offices.get(office).active()) throw conflict("SSO 조직 대응의 활성 로컬 사업소가 없습니다: " + row.ssoOfficeCode());
            if (!department.isBlank()) {
                var value = departments.get(key(office, department));
                if (value == null || !value.active()) throw conflict("SSO 조직 대응의 활성 로컬 부서가 없습니다: " + row.ssoDepartmentCode());
            }
        }
    }

    private void validateAffiliations(List<PersonRow> importedPeople, Map<String, OrganizationMappingService.Mapping> finalMappings,
            Map<String, DirectoryService.Person> finalPeople) {
        Map<String, UserSsoOrganizationService.Affiliation> finalAffiliations = new LinkedHashMap<>();
        affiliations.list().forEach(item -> finalAffiliations.put(item.username(), item));
        importedPeople.forEach(row -> finalAffiliations.put(row.username(), new UserSsoOrganizationService.Affiliation(
                row.username(), text(row.ssoOfficeCode()), text(row.ssoDepartmentCode()), 0)));
        for (var affiliation : finalAffiliations.values()) {
            var mapping = finalMappings.get(key(affiliation.ssoOfficeCode(), affiliation.ssoDepartmentCode()));
            var person = finalPeople.get(affiliation.username());
            if (person == null || mapping == null || person.active() && !mapping.active()) {
                throw conflict("사용자의 SSO 조직 대응이 없습니다: " + affiliation.username());
            }
            if (person.active() && (!person.officeCode().equals(mapping.officeCode()) || !person.departmentCode().equals(mapping.departmentCode()))) {
                throw conflict("사용자의 로컬 소속과 SSO 조직 대응이 일치하지 않습니다: " + affiliation.username());
            }
        }
    }

    private void validateRanks(RankGroupRow group, List<RankCodeRow> ranks, Map<String, DirectoryService.Person> people) {
        if (!ranks.isEmpty() && !codes.groupSummary().containsKey(RANK_GROUP) && group == null) throw conflict("CM008 그룹 정보가 필요합니다.");
        Set<String> available = new LinkedHashSet<>();
        boolean groupActive = group != null ? active(group.useFlag()) : codes.definitions().stream()
                .filter(item -> item.code().equals(RANK_GROUP)).findFirst().map(CommonCodeService.CodeGroup::active).orElse(false);
        if (groupActive && codes.groupSummary().containsKey(RANK_GROUP)) codes.findItems(RANK_GROUP, false, null).stream()
                .filter(CommonCodeService.CommonCodeItem::active).forEach(item -> available.add(item.code()));
        ranks.forEach(row -> { if (groupActive && active(row.useFlag())) available.add(row.code()); else available.remove(row.code()); });
        for (var person : people.values()) {
            String rank = person.rankCode();
            if (person.active() && !rank.isBlank() && !available.contains(rank)) throw conflict("사용자의 직급 코드가 없습니다: " + person.username() + "/" + rank);
        }
    }

    private void validateRows(ImportRequest request) {
        request.offices().forEach(row -> { code(row.code()); required(row.name(), 100); length(row.abbreviation(), 100); length(row.address(), 500); length(row.telephone(), 30); active(row.useFlag()); });
        request.departments().forEach(row -> { code(row.code()); code(row.officeCode()); required(row.name(), 100); length(row.description(), 2000); active(row.useFlag()); });
        request.organizationMappings().forEach(row -> { required(row.ssoOfficeCode(), 100); required(row.ssoDepartmentCode(), 100); optionalCode(row.officeCode()); optionalCode(row.departmentCode()); active(row.useFlag()); });
        request.regions().forEach(row -> { code(row.code()); code(row.officeCode()); required(row.name(), 100); length(row.districtCode(), 50); length(row.districtName(), 100); active(row.useFlag()); });
        request.people().forEach(row -> { code(row.username()); required(row.name(), 100); code(row.officeCode()); optionalCode(row.departmentCode());
            required(row.ssoOfficeCode(), 100); required(row.ssoDepartmentCode(), 100); length(row.rankCode(), 50); length(row.telephone(), 30);
            length(row.mobile(), 30); length(row.email(), 150); length(row.jobDescription(), 1000); active(row.useFlag()); });
        request.userRegions().forEach(row -> { code(row.username()); code(row.regionCode()); });
        if (request.rankGroup() != null) { required(request.rankGroup().name(), 100); active(request.rankGroup().useFlag()); }
        request.rankCodes().forEach(row -> { code(row.code()); required(row.name(), 100); active(row.useFlag());
            active(row.screen1()); active(row.screen2()); active(row.screen3()); active(row.screen4()); active(row.screen5());
            if (row.sortOrder() < 0 || row.sortOrder() > 9999) throw new IllegalArgumentException("직급 정렬 순서를 확인해 주세요."); });
    }

    private int differences(DirectoryService.DirectoryView old, Map<String, DirectoryService.Office> offices,
            Map<String, DirectoryService.Department> departments, Map<String, DirectoryService.Region> regions,
            Map<String, DirectoryService.Person> people, Set<String> officeKeys, Set<String> departmentKeys,
            Set<String> regionKeys, Set<String> personKeys) {
        var oldOffices = indexOffices(old.offices()); var oldDepartments = indexDepartments(old.departments());
        var oldRegions = indexRegions(old.regions()); var oldPeople = indexPeople(old.people()); int count = 0;
        for (String key : officeKeys) if (!Objects.equals(oldOffices.get(key), offices.get(key))) count++;
        for (String key : departmentKeys) if (!Objects.equals(oldDepartments.get(key), departments.get(key))) count++;
        for (String key : regionKeys) if (!Objects.equals(oldRegions.get(key), regions.get(key))) count++;
        for (String key : personKeys) if (!Objects.equals(oldPeople.get(key), people.get(key))) count++;
        return count;
    }

    private static Map<String, DirectoryService.Office> indexOffices(List<DirectoryService.Office> values) {
        Map<String, DirectoryService.Office> result = new LinkedHashMap<>(); values.forEach(item -> result.put(item.code(), item)); return result;
    }
    private static Map<String, DirectoryService.Department> indexDepartments(List<DirectoryService.Department> values) {
        Map<String, DirectoryService.Department> result = new LinkedHashMap<>(); values.forEach(item -> result.put(key(item.officeCode(), item.code()), item)); return result;
    }
    private static Map<String, DirectoryService.Region> indexRegions(List<DirectoryService.Region> values) {
        Map<String, DirectoryService.Region> result = new LinkedHashMap<>(); values.forEach(item -> result.put(item.code(), item)); return result;
    }
    private static Map<String, DirectoryService.Person> indexPeople(List<DirectoryService.Person> values) {
        Map<String, DirectoryService.Person> result = new LinkedHashMap<>(); values.forEach(item -> result.put(item.username(), item)); return result;
    }
    private static <T> Set<String> unique(List<T> rows, java.util.function.Function<T, String> key, String label) {
        Set<String> result = new LinkedHashSet<>();
        for (T row : rows) { String value = row == null ? "" : text(key.apply(row));
            if (value.isBlank() || !result.add(value)) throw new IllegalArgumentException(label + " 키가 비어 있거나 중복입니다."); }
        return result;
    }
    private static void code(String value) { if (value == null || !value.matches("[A-Za-z0-9_]{1,50}")) throw new IllegalArgumentException("코드 형식을 확인해 주세요."); }
    private static void optionalCode(String value) { if (value != null && !value.isBlank()) code(value); }
    private static void length(String value, int max) { if (value != null && value.length() > max) throw new IllegalArgumentException("입력값의 길이를 확인해 주세요."); }
    private static void required(String value, int max) { if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("필수 문자열을 확인해 주세요."); }
    private static boolean active(String flag) { if ("Y".equals(flag)) return true; if ("N".equals(flag)) return false; throw new IllegalArgumentException("useFlag는 Y 또는 N이어야 합니다."); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String key(String first, String second) { return text(first) + ":" + text(second); }
    private static ApiException conflict(String message) { return new ApiException(ErrorCode.CONFLICT, message); }

    public record ImportRequest(List<OfficeRow> offices, List<DepartmentRow> departments,
            List<MappingRow> organizationMappings, List<RegionRow> regions, List<PersonRow> people,
            List<UserRegionRow> userRegions, RankGroupRow rankGroup, List<RankCodeRow> rankCodes) {
        static ImportRequest empty() { return new ImportRequest(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of()); }
        ImportRequest normalized() { return new ImportRequest(list(offices), list(departments), list(organizationMappings), list(regions),
                list(people), list(userRegions), rankGroup, list(rankCodes)); }
        private static <T> List<T> list(List<T> value) { return value == null ? List.of() : List.copyOf(value); }
    }
    public record OfficeRow(String code, String name, String abbreviation, String address, String telephone, String useFlag) { }
    public record DepartmentRow(String code, String officeCode, String name, String description, String useFlag) { }
    public record MappingRow(String ssoOfficeCode, String ssoDepartmentCode, String ssoOfficeName, String ssoDepartmentName,
            String officeCode, String departmentCode, String useFlag) { }
    public record RegionRow(String code, String officeCode, String districtCode, String districtName, String name, String useFlag) { }
    public record PersonRow(String username, String name, String officeCode, String departmentCode, String ssoOfficeCode,
            String ssoDepartmentCode, String rankCode, String telephone, String mobile, String email, String jobDescription, String useFlag) { }
    public record UserRegionRow(String username, String regionCode) { }
    public record RankGroupRow(String name, String description, String useFlag) { }
    public record RankCodeRow(String code, String name, String description, int sortOrder, String useFlag,
            String screen1, String screen2, String screen3, String screen4, String screen5,
            String substituteName, String screenDescription) { }
    public record ImportResult(int offices, int departments, int organizationMappings, int regions,
            int people, int userRegions, int rankCodes, int changed) { }
}
