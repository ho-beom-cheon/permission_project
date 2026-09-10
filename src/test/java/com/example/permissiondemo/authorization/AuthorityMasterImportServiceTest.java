package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.example.permissiondemo.authorization.AuthorityMasterImportService.SourceAuthorityRow;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorityMasterImportServiceTest {

    private AuthorizationCatalog catalog;
    private AuthorityMasterImportService importer;

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        importer = new AuthorityMasterImportService(catalog);
    }

    @Test
    void importsExactSourceValuesAndDerivesSystemWithoutLinkingClassification() {
        var result = importer.importRows(List.of(
                row("AI0201002002", "사업소 심사 담당", "원본 설명", "Y", "AI0201"),
                row("AB0204001002", "대행 심사 담당", null, "N", "AB0204"),
                row("CY0201004004", "사업소민원처리", "", "Y", null),
                row("MO0000000001", "모바일 현장 권한", "모바일 원본 설명", "Y", "MO0001")));

        assertThat(result.created()).containsExactly(
                "AI0201002002", "AB0204001002", "CY0201004004", "MO0000000001");
        assertThat(result.updated()).isEmpty();
        assertThat(result.unchanged()).isEmpty();
        assertThat(result.deferredClassificationIds())
                .containsEntry("AI0201002002", "AI0201")
                .containsEntry("AB0204001002", "AB0204")
                .containsEntry("MO0000000001", "MO0001")
                .hasSize(3);
        assertThat(catalog.findAuthority("AI0201002002")).contains(
                new AuthorityDefinition("AI0201002002", "사업소 심사 담당", true, "AI", null, "원본 설명"));
        assertThat(catalog.findAuthority("AB0204001002")).contains(
                new AuthorityDefinition("AB0204001002", "대행 심사 담당", false, "AB", null, ""));
        assertThat(catalog.findAuthority("CY0201004004")).get().extracting(AuthorityDefinition::systemId)
                .isEqualTo("CY");
        assertThat(catalog.findAuthority("MO0000000001")).contains(
                new AuthorityDefinition("MO0000000001", "모바일 현장 권한", true, "MO", null, "모바일 원본 설명"));
    }

    @Test
    void reportsCreatedUpdatedAndUnchangedAndDoesNotVersionIdenticalRows() {
        importer.importRows(List.of(row("AI0000000001", "기존", "설명", "Y", null)));
        long afterCreate = catalog.authorityVersion();

        var same = importer.importRows(List.of(row("AI0000000001", "기존", "설명", "Y", null)));
        assertThat(same.unchanged()).containsExactly("AI0000000001");
        assertThat(catalog.authorityVersion()).isEqualTo(afterCreate);

        var changed = importer.importRows(List.of(
                row("AI0000000001", "변경", "설명", "Y", null),
                row("AB0000000001", "신규", "", "N", null)));
        assertThat(changed.updated()).containsExactly("AI0000000001");
        assertThat(changed.created()).containsExactly("AB0000000001");
        assertThat(catalog.authorityVersion()).isEqualTo(afterCreate + 2);
    }

    @Test
    void validatesEveryRowBeforeChangingCatalog() {
        long before = catalog.authorityVersion();

        assertThatThrownBy(() -> importer.importRows(List.of(
                row("AI0000000002", "정상", "", "Y", null),
                row("ZZ0000000001", "지원하지 않음", "", "Y", null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(catalog.findAuthority("AI0000000002")).isEmpty();
        assertThat(catalog.authorityVersion()).isEqualTo(before);
    }

    @Test
    void rejectsDuplicateIdsBlankFieldsInvalidFlagsAndOversizedValues() {
        assertInvalid(List.of(row("AI0000000001", "A", "", "Y", null),
                row("AI0000000001", "B", "", "N", null)));
        assertInvalid(List.of(row("AI0000000001", " ", "", "Y", null)));
        assertInvalid(List.of(row("AI0000000001", "이름", "", "y", null)));
        assertInvalid(List.of(row("AI0000000001", "이".repeat(101), "", "Y", null)));
        assertInvalid(List.of(row("AI0000000001", "이름", "설".repeat(201), "Y", null)));
        assertInvalid(List.of(row("AI0000000001", " 이름", "", "Y", null)));
        assertInvalid(List.of(row("AI0000000001", "이름", "", "Y", " ")));
    }

    @Test
    void rejectsEmptyInputNullRowsAndNonExactAuthorityIds() {
        assertThatThrownBy(() -> importer.importRows(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.importRows(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.importRows(java.util.Arrays.asList((SourceAuthorityRow) null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertInvalid(List.of(row("AI123", "이름", "", "Y", null)));
        assertInvalid(List.of(row("ZZ0000000001", "이름", "", "Y", null)));
        assertInvalid(List.of(row("AI000000000A", "이름", "", "Y", null)));
    }

    private void assertInvalid(List<SourceAuthorityRow> rows) {
        assertThatThrownBy(() -> importer.importRows(rows)).isInstanceOf(IllegalArgumentException.class);
    }

    private SourceAuthorityRow row(String id, String name, String description, String useFlag, String classificationId) {
        return new SourceAuthorityRow(id, name, description, useFlag, classificationId);
    }
}
