package com.example.permissiondemo.project;

import java.util.List;
import java.util.Optional;

import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** oracle 프로필에서 MyBatis XML Mapper로 프로젝트와 참여자를 저장한다. */
@Repository
@Profile("oracle")
public class MyBatisProjectRepository implements ProjectRepository {

    private final ProjectMapper projectMapper;

    public MyBatisProjectRepository(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    public List<ProjectModels.Project> findAll() {
        return projectMapper.selectAll();
    }

    @Override
    public ProjectModels.ProjectPage search(ProjectModels.ProjectSearchCriteria criteria) {
        String keywordPattern = criteria.keyword().isEmpty()
                ? ""
                : "%" + escapeLike(criteria.normalizedKeyword()) + "%";
        long totalElements = projectMapper.count(criteria, keywordPattern);
        if (totalElements == 0) {
            return ProjectModels.ProjectPage.of(List.of(), 0, criteria.page(), criteria.size());
        }
        long offset = (long) criteria.page() * criteria.size();
        return ProjectModels.ProjectPage.of(
                projectMapper.search(criteria, keywordPattern, offset, criteria.size()),
                totalElements,
                criteria.page(),
                criteria.size());
    }

    @Override
    public Optional<ProjectModels.Project> findById(String projectId) {
        return Optional.ofNullable(projectMapper.selectById(projectId));
    }

    @Override
    @Transactional
    public ProjectModels.Project save(ProjectModels.Project project) {
        if (project.version() == 0) {
            try {
                projectMapper.insertProject(project);
            } catch (DuplicateKeyException exception) {
                throw new ApiException(ErrorCode.CONFLICT, project.id());
            }
        } else if (projectMapper.updateProject(project) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, project.id());
        }
        return findById(project.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, project.id()));
    }

    @Override
    @Transactional
    public ProjectModels.Project transferOwnership(
            ProjectModels.Project project,
            String previousOwnerUsername) {
        ProjectModels.Project saved = save(project);
        projectMapper.demoteOtherOwners(saved.id(), saved.ownerUsername());
        saveMember(new ProjectModels.ProjectMember(
                saved.id(), saved.ownerUsername(), ProjectModels.ProjectRole.OWNER, true));
        return saved;
    }

    @Override
    public List<ProjectModels.ProjectMember> findMembers(String projectId) {
        return projectMapper.selectMembers(projectId);
    }

    @Override
    @Transactional
    public ProjectModels.ProjectMember saveMember(ProjectModels.ProjectMember member) {
        ProjectModels.Project project = findById(member.projectId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, member.projectId()));
        if (project.ownerUsername().equals(member.username())
                && (member.role() != ProjectModels.ProjectRole.OWNER || !member.active())) {
            throw new ApiException(ErrorCode.CONFLICT, member.username());
        }
        if (member.role() == ProjectModels.ProjectRole.OWNER
                && !project.ownerUsername().equals(member.username())) {
            throw new ApiException(ErrorCode.CONFLICT, member.username());
        }
        if (projectMapper.updateMember(member) == 0) {
            projectMapper.insertMember(member);
        }
        return member;
    }

    @Override
    public void deleteMember(String projectId, String username) {
        ProjectModels.Project project = findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, projectId));
        if (project.ownerUsername().equals(username)) {
            throw new ApiException(ErrorCode.CONFLICT, username);
        }
        if (projectMapper.deleteMember(projectId, username) == 0) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username);
        }
    }

    @Override
    public List<ProjectModels.ProjectStatusHistory> findStatusHistory(String projectId) {
        return projectMapper.selectStatusHistory(projectId);
    }

    @Override
    @Transactional
    public ProjectModels.ProjectStatusHistory appendStatusHistory(
            ProjectModels.ProjectStatusHistory history) {
        ProjectModels.ProjectStatusHistory saved = new ProjectModels.ProjectStatusHistory(
                history.projectId(),
                projectMapper.selectNextStatusHistorySequence(history.projectId()),
                history.fromStatus(),
                history.toStatus(),
                history.changedBy(),
                history.reason(),
                history.changedAt());
        if (projectMapper.insertStatusHistory(saved) != 1) {
            throw new ApiException(ErrorCode.CONFLICT, history.projectId());
        }
        return saved;
    }

    /** Oracle LIKE 와일드카드를 일반 검색 문자로 취급하도록 이스케이프한다. */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
