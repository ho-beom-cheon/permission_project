package com.example.permissiondemo.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Oracle Mapper 호출 계약과 페이징·오너·상태 이력 조정을 DB 없이 검증한다. */
class MyBatisProjectRepositoryTest {

    private ProjectMapper mapper;
    private MyBatisProjectRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(ProjectMapper.class);
        repository = new MyBatisProjectRepository(mapper);
    }

    @Test
    void searchUsesCountAndDatabasePagingWithEscapedLikePattern() {
        ProjectModels.ProjectSearchCriteria criteria = new ProjectModels.ProjectSearchCriteria(
                "A_%\\B",
                Set.of(ProjectModels.ProjectStatus.ACTIVE),
                "admin",
                true,
                ProjectModels.ProjectSort.START_DATE,
                ProjectModels.SortDirection.DESC,
                1,
                10);
        ProjectModels.Project project = project("PRJ_ACCESS_RENEWAL", "admin", 2);
        String expectedPattern = "%a\\_\\%\\\\b%";
        when(mapper.count(criteria, expectedPattern)).thenReturn(21L);
        when(mapper.search(criteria, expectedPattern, 10L, 10)).thenReturn(List.of(project));

        ProjectModels.ProjectPage result = repository.search(criteria);

        assertThat(result.projects()).containsExactly(project);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(3);
        verify(mapper, never()).selectAll();
    }

    @Test
    void ownershipTransferUpdatesProjectThenRepairsOwnerMemberships() {
        ProjectModels.Project request = project("PRJ_ACCESS_RENEWAL", "manager", 1);
        ProjectModels.Project saved = project("PRJ_ACCESS_RENEWAL", "manager", 2);
        when(mapper.updateProject(request)).thenReturn(1);
        when(mapper.selectById(request.id())).thenReturn(saved);
        when(mapper.updateMember(any())).thenReturn(1);

        assertThat(repository.transferOwnership(request, "admin")).isEqualTo(saved);

        verify(mapper).demoteOtherOwners(saved.id(), "manager");
        ArgumentCaptor<ProjectModels.ProjectMember> memberCaptor =
                ArgumentCaptor.forClass(ProjectModels.ProjectMember.class);
        verify(mapper).updateMember(memberCaptor.capture());
        assertThat(memberCaptor.getValue())
                .isEqualTo(new ProjectModels.ProjectMember(
                        saved.id(), "manager", ProjectModels.ProjectRole.OWNER, true));
    }

    @Test
    void statusHistoryReceivesNextProjectSequenceBeforeInsert() {
        ProjectModels.ProjectStatusHistory request = new ProjectModels.ProjectStatusHistory(
                "PRJ_ACCESS_RENEWAL",
                0,
                ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE,
                "admin",
                "개발 착수",
                Instant.parse("2026-09-01T00:00:00Z"));
        when(mapper.selectNextStatusHistorySequence(request.projectId())).thenReturn(4L);
        when(mapper.insertStatusHistory(any())).thenReturn(1);

        ProjectModels.ProjectStatusHistory saved = repository.appendStatusHistory(request);

        assertThat(saved.sequence()).isEqualTo(4);
        verify(mapper).insertStatusHistory(saved);
    }

    @Test
    void projectMapperXmlParsesAllOracleStatements() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream("mappers/ProjectMapper.xml")) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mappers/ProjectMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }

        ProjectModels.ProjectSearchCriteria criteria = new ProjectModels.ProjectSearchCriteria(
                "권한",
                Set.of(ProjectModels.ProjectStatus.ACTIVE),
                "admin",
                true,
                ProjectModels.ProjectSort.STATUS,
                ProjectModels.SortDirection.DESC,
                0,
                20);
        BoundSql searchSql = configuration
                .getMappedStatement(ProjectMapper.class.getName() + ".search")
                .getBoundSql(Map.of(
                        "criteria", criteria,
                        "keywordPattern", "%권한%",
                        "offset", 0L,
                        "size", 20));

        assertThat(searchSql.getSql().replaceAll("\\s+", " "))
                .contains("PROJECT_STATUS IN", "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(configuration.hasStatement(
                ProjectMapper.class.getName() + ".demoteOtherOwners")).isTrue();
        assertThat(configuration.hasStatement(
                ProjectMapper.class.getName() + ".selectStatusHistory")).isTrue();
        assertThat(configuration.hasStatement(
                ProjectMapper.class.getName() + ".selectNextStatusHistorySequence")).isTrue();
        assertThat(configuration.hasStatement(
                ProjectMapper.class.getName() + ".insertStatusHistory")).isTrue();
    }

    private ProjectModels.Project project(String id, String owner, long version) {
        return new ProjectModels.Project(
                id,
                "통합 권한 체계 고도화",
                "권한 관리 개선",
                ProjectModels.ProjectStatus.ACTIVE,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 30),
                owner,
                true,
                version);
    }
}
