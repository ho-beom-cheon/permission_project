package com.example.permissiondemo.authorization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.storage.StateBoundary;

import org.springframework.stereotype.Service;

/** 원본 권한기본의 1단계 필드를 검증한 뒤 현재 권한 카탈로그에 그대로 반영한다. */
@Service
@StateBoundary
public class AuthorityMasterImportService {

    private static final Pattern SOURCE_AUTHORITY_ID = Pattern.compile("(?:AI|AB|CY|MO)[0-9]{10}");
    private static final Pattern OPTIONAL_CLASSIFICATION_ID = Pattern.compile("[A-Z0-9_]{1,50}");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private final AuthorizationCatalog catalog;

    public AuthorityMasterImportService(AuthorizationCatalog catalog) {
        this.catalog = catalog;
    }

    /** 모든 원본 행을 먼저 검증하고, 변경된 권한만 생성 또는 갱신한다. */
    public ImportResult importRows(List<SourceAuthorityRow> sourceRows) {
        synchronized (catalog) {
            List<ValidatedRow> rows = validateAll(sourceRows);
            List<String> created = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            List<String> unchanged = new ArrayList<>();
            Map<String, String> deferredClassifications = new LinkedHashMap<>();

            for (ValidatedRow row : rows) {
                if (row.sourceClassificationId() != null) {
                    deferredClassifications.put(row.definition().id(), row.sourceClassificationId());
                }
                AuthorityDefinition existing = catalog.findAuthority(row.definition().id()).orElse(null);
                if (row.definition().equals(existing)) {
                    unchanged.add(row.definition().id());
                    continue;
                }
                catalog.saveAuthority(row.definition());
                if (existing == null) created.add(row.definition().id());
                else updated.add(row.definition().id());
            }
            return new ImportResult(List.copyOf(created), List.copyOf(updated), List.copyOf(unchanged),
                    Collections.unmodifiableMap(new LinkedHashMap<>(deferredClassifications)));
        }
    }

    private List<ValidatedRow> validateAll(List<SourceAuthorityRow> sourceRows) {
        if (sourceRows == null || sourceRows.isEmpty()) {
            throw new IllegalArgumentException("이관할 권한 원본 행이 필요합니다.");
        }
        Set<String> seen = new HashSet<>();
        List<ValidatedRow> validated = new ArrayList<>(sourceRows.size());
        for (SourceAuthorityRow row : sourceRows) {
            if (row == null) throw new IllegalArgumentException("권한 원본 행은 null일 수 없습니다.");
            if (row.authId() == null || !SOURCE_AUTHORITY_ID.matcher(row.authId()).matches()) {
                throw new IllegalArgumentException("권한 ID는 AI, AB, CY, MO와 숫자 10자리 조합이어야 합니다.");
            }
            if (!seen.add(row.authId())) {
                throw new IllegalArgumentException("중복 권한 ID가 포함되어 있습니다: " + row.authId());
            }
            requireExactText(row.authNm(), MAX_NAME_LENGTH, false, "권한명");
            requireExactText(row.authDsc(), MAX_DESCRIPTION_LENGTH, true, "권한설명");
            if (!"Y".equals(row.useFlag()) && !"N".equals(row.useFlag())) {
                throw new IllegalArgumentException("사용여부는 Y 또는 N이어야 합니다.");
            }
            if (row.authClsfId() != null && (!OPTIONAL_CLASSIFICATION_ID.matcher(row.authClsfId()).matches()
                    || !row.authClsfId().equals(row.authClsfId().trim()))) {
                throw new IllegalArgumentException("권한분류 ID 형식을 확인해 주세요.");
            }
            String description = row.authDsc() == null ? "" : row.authDsc();
            String systemId = row.authId().substring(0, 2);
            // 분류 마스터 이관 전에는 원본 분류 ID를 결과에 보존하되 카탈로그 연결은 만들지 않는다.
            AuthorityDefinition definition = new AuthorityDefinition(row.authId(), row.authNm(),
                    "Y".equals(row.useFlag()), systemId, null, description);
            validated.add(new ValidatedRow(definition, row.authClsfId()));
        }
        return List.copyOf(validated);
    }

    private void requireExactText(String value, int maximum, boolean optional, String fieldName) {
        if (value == null) {
            if (optional) return;
            throw new IllegalArgumentException(fieldName + "은 필수입니다.");
        }
        if ((!optional && value.isBlank()) || value.length() > maximum || !value.equals(value.trim())) {
            throw new IllegalArgumentException(fieldName + "의 공백과 길이를 확인해 주세요.");
        }
        if (optional && value.isBlank() && !value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "은 빈 문자열 또는 실제 값이어야 합니다.");
        }
    }

    public record SourceAuthorityRow(
            String authId,
            String authNm,
            String authDsc,
            String useFlag,
            String authClsfId) { }

    public record ImportResult(
            List<String> created,
            List<String> updated,
            List<String> unchanged,
            Map<String, String> deferredClassificationIds) { }

    private record ValidatedRow(AuthorityDefinition definition, String sourceClassificationId) { }
}
