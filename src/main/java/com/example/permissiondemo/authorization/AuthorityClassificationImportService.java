package com.example.permissiondemo.authorization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityClassification;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.stereotype.Service;

/** 원본 권한분류 트리와 1단계에서 보류한 권한 연결을 전체 검증 후 원자적으로 반영한다. */
@Service
@StateBoundary
public class AuthorityClassificationImportService {

    private static final Pattern SOURCE_CLASSIFICATION_ID = Pattern.compile("(?:AI|AB|CY|MO)[0-9]{4}");
    private static final Pattern SOURCE_AUTHORITY_ID = Pattern.compile("(?:AI|AB|CY|MO)[0-9]{10}");
    private static final int MAX_NAME_LENGTH = 100;

    private final AuthorizationCatalog catalog;

    public AuthorityClassificationImportService(AuthorizationCatalog catalog) {
        this.catalog = catalog;
    }

    public ImportResult importRows(ImportRequest request) {
        synchronized (catalog) {
            ValidatedImport validated = validateAll(request);
            List<String> createdClassifications = new ArrayList<>();
            List<String> updatedClassifications = new ArrayList<>();
            List<String> unchangedClassifications = new ArrayList<>();
            Map<String, AuthorityClassification> existingClassifications = byClassificationId(catalog.classifications());
            for (AuthorityClassification classification : validated.classifications()) {
                AuthorityClassification previous = existingClassifications.get(classification.id());
                if (previous == null) createdClassifications.add(classification.id());
                else if (previous.equals(classification)) unchangedClassifications.add(classification.id());
                else updatedClassifications.add(classification.id());
            }

            List<String> createdAuthorityLinks = new ArrayList<>();
            List<String> updatedAuthorityLinks = new ArrayList<>();
            List<String> unchangedAuthorityLinks = new ArrayList<>();
            for (Map.Entry<String, String> link : validated.authorityLinks().entrySet()) {
                String previous = catalog.findAuthority(link.getKey()).orElseThrow().classificationId();
                if (link.getValue().equals(previous)) unchangedAuthorityLinks.add(link.getKey());
                else if (previous == null) createdAuthorityLinks.add(link.getKey());
                else updatedAuthorityLinks.add(link.getKey());
            }

            catalog.applyClassificationImport(validated.classifications(), validated.authorityLinks());
            return new ImportResult(
                    List.copyOf(createdClassifications), List.copyOf(updatedClassifications),
                    List.copyOf(unchangedClassifications), List.copyOf(createdAuthorityLinks),
                    List.copyOf(updatedAuthorityLinks), List.copyOf(unchangedAuthorityLinks));
        }
    }

    private ValidatedImport validateAll(ImportRequest request) {
        if (request == null || request.classifications() == null || request.authorityLinks() == null
                || (request.classifications().isEmpty() && request.authorityLinks().isEmpty())) {
            throw new IllegalArgumentException("이관할 권한분류 또는 권한 연결이 필요합니다.");
        }

        Set<String> classificationIds = new HashSet<>();
        List<AuthorityClassification> classifications = new ArrayList<>();
        for (ClassificationRow row : request.classifications()) {
            if (row == null) throw new IllegalArgumentException("권한분류 원본 행은 null일 수 없습니다.");
            requireSourceId(row.authClsfId(), "권한분류 ID");
            if (!classificationIds.add(row.authClsfId())) {
                throw new IllegalArgumentException("중복 권한분류 ID가 포함되어 있습니다: " + row.authClsfId());
            }
            if (row.upperAuthClsfId() != null) requireSourceId(row.upperAuthClsfId(), "상위 권한분류 ID");
            requireName(row.authClsfNm());
            if (!"Y".equals(row.useFlag()) && !"N".equals(row.useFlag())) {
                throw new IllegalArgumentException("사용여부는 Y 또는 N이어야 합니다.");
            }
            classifications.add(new AuthorityClassification(
                    row.authClsfId(), row.upperAuthClsfId(), row.authClsfNm(),
                    systemOf(row.authClsfId()), "Y".equals(row.useFlag())));
        }

        Map<String, AuthorityClassification> merged = byClassificationId(catalog.classifications());
        classifications.forEach(item -> merged.put(item.id(), item));
        validateGraph(merged);

        Set<String> linkedAuthorityIds = new HashSet<>();
        Map<String, String> authorityLinks = new LinkedHashMap<>();
        for (AuthorityLink link : request.authorityLinks()) {
            if (link == null) throw new IllegalArgumentException("권한분류 연결 행은 null일 수 없습니다.");
            if (link.authId() == null || !SOURCE_AUTHORITY_ID.matcher(link.authId()).matches()) {
                throw new IllegalArgumentException("권한 ID는 AI, AB, CY, MO와 숫자 10자리 조합이어야 합니다.");
            }
            requireSourceId(link.authClsfId(), "권한분류 ID");
            if (!linkedAuthorityIds.add(link.authId())) {
                throw new IllegalArgumentException("중복 권한 연결이 포함되어 있습니다: " + link.authId());
            }
            AuthorityDefinition authority = catalog.findAuthority(link.authId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 권한입니다: " + link.authId()));
            AuthorityClassification classification = merged.get(link.authClsfId());
            if (classification == null) {
                throw new IllegalArgumentException("존재하지 않는 권한분류입니다: " + link.authClsfId());
            }
            if (!authority.systemId().equals(classification.systemId())) {
                throw new IllegalArgumentException("권한과 권한분류의 시스템이 일치하지 않습니다: " + link.authId());
            }
            authorityLinks.put(link.authId(), link.authClsfId());
        }
        return new ValidatedImport(List.copyOf(classifications),
                Collections.unmodifiableMap(new LinkedHashMap<>(authorityLinks)));
    }

    private void validateGraph(Map<String, AuthorityClassification> graph) {
        for (AuthorityClassification classification : graph.values()) {
            String parentId = classification.parentId();
            if (parentId == null) continue;
            AuthorityClassification parent = graph.get(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("존재하지 않는 상위 권한분류입니다: " + parentId);
            }
            if (!classification.systemId().equals(parent.systemId())) {
                throw new IllegalArgumentException("상하위 권한분류의 시스템이 일치하지 않습니다: " + classification.id());
            }
        }
        for (AuthorityClassification start : graph.values()) {
            Set<String> path = new HashSet<>();
            AuthorityClassification current = start;
            while (current != null) {
                if (!path.add(current.id())) {
                    throw new IllegalArgumentException("권한분류에 순환 관계가 있습니다: " + current.id());
                }
                current = current.parentId() == null ? null : graph.get(current.parentId());
            }
        }
    }

    private Map<String, AuthorityClassification> byClassificationId(List<AuthorityClassification> source) {
        Map<String, AuthorityClassification> result = new HashMap<>();
        source.forEach(item -> result.put(item.id(), item));
        return result;
    }

    private void requireSourceId(String id, String fieldName) {
        if (id == null || !SOURCE_CLASSIFICATION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(fieldName + "는 AI, AB, CY, MO와 숫자 4자리 조합이어야 합니다.");
        }
    }

    private void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || !name.equals(name.trim())) {
            throw new IllegalArgumentException("권한분류명의 공백과 길이를 확인해 주세요.");
        }
    }

    private String systemOf(String classificationId) {
        return classificationId.substring(0, 2);
    }

    public record ClassificationRow(
            String authClsfId,
            String upperAuthClsfId,
            String authClsfNm,
            String useFlag) { }

    public record AuthorityLink(String authId, String authClsfId) { }

    public record ImportRequest(
            List<ClassificationRow> classifications,
            List<AuthorityLink> authorityLinks) { }

    public record ImportResult(
            List<String> createdClassifications,
            List<String> updatedClassifications,
            List<String> unchangedClassifications,
            List<String> createdAuthorityLinks,
            List<String> updatedAuthorityLinks,
            List<String> unchangedAuthorityLinks) { }

    private record ValidatedImport(
            List<AuthorityClassification> classifications,
            Map<String, String> authorityLinks) { }
}
