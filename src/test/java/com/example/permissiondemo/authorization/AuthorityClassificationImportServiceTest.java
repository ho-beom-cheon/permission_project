package com.example.permissiondemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.example.permissiondemo.authorization.AuthorityClassificationImportService.AuthorityLink;
import com.example.permissiondemo.authorization.AuthorityClassificationImportService.ClassificationRow;
import com.example.permissiondemo.authorization.AuthorityClassificationImportService.ImportRequest;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityClassification;
import com.example.permissiondemo.authorization.AuthorizationCatalog.AuthorityDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorityClassificationImportServiceTest {

    private AuthorizationCatalog catalog;
    private AuthorityClassificationImportService importer;

    @BeforeEach
    void setUp() {
        catalog = new AuthorizationCatalog();
        importer = new AuthorityClassificationImportService(catalog);
    }

    @Test
    void importsChildBeforeParentAndLinksInactiveSourceStateInOneVersion() {
        catalog.saveAuthority(authority("MO0000000001", "MO"));
        long before = catalog.authorityVersion();

        var result = importer.importRows(new ImportRequest(List.of(
                row("MO0002", "MO0001", "하위", "N"),
                row("MO0001", null, "상위", "N")),
                List.of(new AuthorityLink("MO0000000001", "MO0002"))));

        assertThat(result.createdClassifications()).containsExactly("MO0002", "MO0001");
        assertThat(result.createdAuthorityLinks()).containsExactly("MO0000000001");
        assertThat(catalog.classifications()).contains(
                new AuthorityClassification("MO0001", null, "상위", "MO", false),
                new AuthorityClassification("MO0002", "MO0001", "하위", "MO", false));
        assertThat(catalog.findAuthority("MO0000000001")).get()
                .extracting(AuthorityDefinition::classificationId).isEqualTo("MO0002");
        assertThat(catalog.authorityVersion()).isEqualTo(before + 1);
    }

    @Test
    void identicalImportIsReportedUnchangedAndDoesNotChangeVersion() {
        catalog.saveAuthority(authority("AI0000000002", "AI"));
        ImportRequest request = new ImportRequest(
                List.of(row("AI0010", null, "동일", "Y")),
                List.of(new AuthorityLink("AI0000000002", "AI0010")));
        importer.importRows(request);
        long version = catalog.authorityVersion();

        var result = importer.importRows(request);

        assertThat(result.unchangedClassifications()).containsExactly("AI0010");
        assertThat(result.unchangedAuthorityLinks()).containsExactly("AI0000000002");
        assertThat(result.createdClassifications()).isEmpty();
        assertThat(result.updatedClassifications()).isEmpty();
        assertThat(catalog.authorityVersion()).isEqualTo(version);
    }

    @Test
    void updatesOnlySpecifiedRowsAndLinksAndPreservesOtherCatalogState() {
        catalog.saveClassification(new AuthorityClassification("KEEP", null, "기존", "INFO", true));
        catalog.saveAuthority(authority("AI0000000003", "AI"));
        catalog.saveAuthority(authority("AI0000000004", "AI"));
        importer.importRows(new ImportRequest(
                List.of(row("AI0020", null, "수정 전", "Y")),
                List.of(new AuthorityLink("AI0000000003", "AI0020"))));
        var before = (AuthorizationCatalog.StoredState) catalog.snapshotState();

        var result = importer.importRows(new ImportRequest(
                List.of(row("AI0020", null, "수정 후", "N"), row("AI0021", "AI0020", "신규", "N")),
                List.of(new AuthorityLink("AI0000000003", "AI0021"))));

        assertThat(result.updatedClassifications()).containsExactly("AI0020");
        assertThat(result.createdClassifications()).containsExactly("AI0021");
        assertThat(result.updatedAuthorityLinks()).containsExactly("AI0000000003");
        assertThat(catalog.classifications()).contains(new AuthorityClassification("KEEP", null, "기존", "INFO", true));
        assertThat(catalog.findAuthority("AI0000000004")).contains(authority("AI0000000004", "AI"));
        var after = (AuthorizationCatalog.StoredState) catalog.snapshotState();
        assertThat(after.assignments()).isEqualTo(before.assignments());
        assertThat(after.menuGrants()).isEqualTo(before.menuGrants());
        assertThat(after.actionGrants()).isEqualTo(before.actionGrants());
        assertThat(after.authorityVersion()).isEqualTo(before.authorityVersion() + 1);
    }

    @Test
    void validatesEveryRowAndGraphBeforeChangingCatalog() {
        long before = catalog.authorityVersion();

        assertInvalid(new ImportRequest(List.of(
                row("AI0030", null, "정상 선행", "Y"),
                row("AI0031", "AI9999", "고아 후행", "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(
                row("AI0032", "AI0033", "순환 1", "Y"),
                row("AI0033", "AI0032", "순환 2", "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(
                row("AI0034", null, "중복 1", "Y"),
                row("AI0034", null, "중복 2", "N")), List.of()));
        assertInvalid(new ImportRequest(List.of(
                row("AI0035", "AB0036", "시스템 불일치", "Y"),
                row("AB0036", null, "다른 시스템", "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(row("AI0037", null, "사용여부 오류", "y")), List.of()));

        assertThat(catalog.findAuthority("AI0030")).isEmpty();
        assertThat(catalog.classifications()).noneMatch(item -> item.id().startsWith("AI003"));
        assertThat(catalog.authorityVersion()).isEqualTo(before);
    }

    @Test
    void rejectsUnknownDuplicateAndCrossSystemAuthorityLinksBeforeMutation() {
        catalog.saveAuthority(authority("AI0000000005", "AI"));
        catalog.saveAuthority(authority("AB0000000005", "AB"));
        long before = catalog.authorityVersion();

        assertInvalid(new ImportRequest(List.of(row("AI0040", null, "분류", "Y")),
                List.of(new AuthorityLink("UNKNOWN", "AI0040"))));
        assertInvalid(new ImportRequest(List.of(row("AI0040", null, "분류", "Y")), List.of(
                new AuthorityLink("AI0000000005", "AI0040"),
                new AuthorityLink("AI0000000005", "AI0040"))));
        assertInvalid(new ImportRequest(List.of(row("AI0040", null, "분류", "Y")),
                List.of(new AuthorityLink("AB0000000005", "AI0040"))));
        assertInvalid(new ImportRequest(List.of(),
                List.of(new AuthorityLink("AI0000000005", "AI9999"))));
        assertInvalid(new ImportRequest(List.of(row("AI0040", null, "분류", "Y")),
                List.of(new AuthorityLink("AI_SHORT", "AI0040"))));

        assertThat(catalog.classifications()).noneMatch(item -> item.id().equals("AI0040"));
        assertThat(catalog.authorityVersion()).isEqualTo(before);
    }

    @Test
    void rejectsNonExactSourceFieldsAndEmptyRequest() {
        assertInvalid(new ImportRequest(List.of(row("AI123", null, "이름", "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(row("ZZ0001", null, "이름", "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(row("AI0001", null, " 이름", "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(row("AI0001", null, "이".repeat(101), "Y")), List.of()));
        assertInvalid(new ImportRequest(List.of(), List.of()));
        assertThatThrownBy(() -> importer.importRows(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalid(ImportRequest request) {
        assertThatThrownBy(() -> importer.importRows(request)).isInstanceOf(IllegalArgumentException.class);
    }

    private ClassificationRow row(String id, String parentId, String name, String useFlag) {
        return new ClassificationRow(id, parentId, name, useFlag);
    }

    private AuthorityDefinition authority(String id, String systemId) {
        return new AuthorityDefinition(id, id, true, systemId, null, "");
    }
}
