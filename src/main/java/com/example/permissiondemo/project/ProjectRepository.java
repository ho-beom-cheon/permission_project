package com.example.permissiondemo.project;

import java.util.List;
import java.util.Optional;

/** 프로젝트와 참여자 영속화 구현을 인메모리와 Oracle 사이에서 교체하는 계약이다. */
public interface ProjectRepository {

    List<ProjectModels.Project> findAll();

    /** 구현체와 무관하게 같은 검색·정렬·페이지 계약으로 프로젝트를 조회한다. */
    ProjectModels.ProjectPage search(ProjectModels.ProjectSearchCriteria criteria);

    Optional<ProjectModels.Project> findById(String projectId);

    ProjectModels.Project save(ProjectModels.Project project);

    /** 프로젝트 저장과 기존·신규 오너 역할 정리를 하나의 원자적 저장 작업으로 수행한다. */
    ProjectModels.Project transferOwnership(
            ProjectModels.Project project,
            String previousOwnerUsername);

    List<ProjectModels.ProjectMember> findMembers(String projectId);

    ProjectModels.ProjectMember saveMember(ProjectModels.ProjectMember member);

    void deleteMember(String projectId, String username);

    List<ProjectModels.ProjectStatusHistory> findStatusHistory(String projectId);

    /** sequence가 0인 신규 이력에 프로젝트별 다음 순번을 부여해 저장한다. */
    ProjectModels.ProjectStatusHistory appendStatusHistory(
            ProjectModels.ProjectStatusHistory history);
}
