package com.example.permissiondemo.project;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 프로젝트 SQL을 XML 매퍼에 위임하는 MyBatis Mapper 계약이다. */
@Mapper
public interface ProjectMapper {

    List<ProjectModels.Project> selectAll();

    List<ProjectModels.Project> search(
            @Param("criteria") ProjectModels.ProjectSearchCriteria criteria,
            @Param("keywordPattern") String keywordPattern,
            @Param("offset") long offset,
            @Param("size") int size);

    long count(
            @Param("criteria") ProjectModels.ProjectSearchCriteria criteria,
            @Param("keywordPattern") String keywordPattern);

    ProjectModels.Project selectById(@Param("projectId") String projectId);

    int insertProject(@Param("project") ProjectModels.Project project);

    int updateProject(@Param("project") ProjectModels.Project project);

    int demoteOtherOwners(
            @Param("projectId") String projectId,
            @Param("ownerUsername") String ownerUsername);

    List<ProjectModels.ProjectMember> selectMembers(@Param("projectId") String projectId);

    int insertMember(@Param("member") ProjectModels.ProjectMember member);

    int updateMember(@Param("member") ProjectModels.ProjectMember member);

    int deleteMember(
            @Param("projectId") String projectId,
            @Param("username") String username);

    List<ProjectModels.ProjectStatusHistory> selectStatusHistory(
            @Param("projectId") String projectId);

    long selectNextStatusHistorySequence(@Param("projectId") String projectId);

    int insertStatusHistory(@Param("history") ProjectModels.ProjectStatusHistory history);
}
