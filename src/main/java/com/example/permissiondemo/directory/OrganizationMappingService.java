package com.example.permissiondemo.directory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.SsoAuthorityScopeService;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** SSO사업소·부서와 로컬 조직의 명시적 대응표. 사용자 소속이나 기존 권한은 변경하지 않는다. */
@Service
@StateBoundary
public class OrganizationMappingService implements StateParticipant {
    private final Map<SsoKey, Mapping> mappings = new LinkedHashMap<>();
    private final DirectoryService directory;
    private final AuditEventService audit;
    private ObjectProvider<SsoAuthorityScopeService> scopes;

    @Autowired
    public void configureScopeInvalidation(ObjectProvider<SsoAuthorityScopeService> scopes) { this.scopes = scopes; }

    public OrganizationMappingService(DirectoryService directory, AuditEventService audit) {
        this.directory = directory;
        this.audit = audit;
    }

    public List<Mapping> list() {
        return mappings.values().stream().sorted(Comparator.comparing(Mapping::ssoOfficeCode)
                .thenComparing(Mapping::ssoDepartmentCode)).toList();
    }

    /** 정확히 일치하는 현재 활성 SSO 조직 대응을 반환한다. */
    public Optional<Mapping> findActive(String ssoOfficeCode, String ssoDepartmentCode) {
        if (ssoOfficeCode == null || ssoDepartmentCode == null) return Optional.empty();
        Mapping mapping = mappings.get(new SsoKey(ssoOfficeCode, ssoDepartmentCode));
        return mapping == null || !mapping.active() ? Optional.empty() : Optional.of(mapping);
    }

    public Mapping save(WriteMapping value) {
        String ssoOffice = required(value.ssoOfficeCode());
        String ssoDepartment = required(value.ssoDepartmentCode());
        String office = text(value.officeCode());
        String department = text(value.departmentCode());
        SsoKey key = new SsoKey(ssoOffice, ssoDepartment);
        Mapping old = mappings.get(key);
        if (value.expectedVersion() != (old == null ? 0 : old.version())) {
            throw new ApiException(ErrorCode.CONFLICT, "조직 대응 정보가 변경되었습니다. 다시 조회해 주세요.");
        }
        var local = directory.view();
        if (!office.isEmpty() && local.offices().stream().noneMatch(item -> item.code().equals(office))) {
            throw new ApiException(ErrorCode.CONFLICT, "등록된 사업소를 선택해 주세요.");
        }
        if (!department.isEmpty() && (office.isEmpty() || local.departments().stream()
                .noneMatch(item -> item.officeCode().equals(office) && item.code().equals(department)))) {
            throw new ApiException(ErrorCode.CONFLICT, "선택한 사업소에 등록된 부서를 입력해 주세요.");
        }
        Mapping saved = new Mapping(ssoOffice, ssoDepartment, text(value.ssoOfficeName()), text(value.ssoDepartmentName()),
                office, department, value.active(), value.expectedVersion() + 1);
        mappings.put(key, saved);
        if (scopes != null && !saved.equals(old)) {
            var service = scopes.getIfAvailable();
            if (service != null) service.invalidateMapping(saved.ssoOfficeCode(), saved.ssoDepartmentCode());
        }
        audit.record("ORGANIZATION_MAPPING_SAVED", "ORGANIZATION_MAPPING", ssoOffice + ":" + ssoDepartment,
                "SUCCESS", Map.of("ssoOfficeCode", ssoOffice, "ssoDepartmentCode", ssoDepartment));
        return saved;
    }

    private String required(String value) {
        String normalized = text(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException("SSO사업소·부서 코드를 입력해 주세요.");
        return normalized;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private record SsoKey(String officeCode, String departmentCode) { }
    public record Mapping(String ssoOfficeCode, String ssoDepartmentCode, String ssoOfficeName, String ssoDepartmentName,
            String officeCode, String departmentCode, boolean active, long version) { }
    public record WriteMapping(String ssoOfficeCode, String ssoDepartmentCode, String ssoOfficeName, String ssoDepartmentName,
            String officeCode, String departmentCode, boolean active, long expectedVersion) { }
    public record StoredState(List<Mapping> mappings) { }

    @Override public String stateKey() { return "organization-mappings"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(list()); }
    @Override public void restoreState(Object raw) {
        mappings.clear();
        if (raw == null || ((StoredState) raw).mappings() == null) return;
        ((StoredState) raw).mappings().forEach(item -> mappings.put(new SsoKey(item.ssoOfficeCode(), item.ssoDepartmentCode()), item));
    }
}
