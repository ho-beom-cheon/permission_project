package com.example.permissiondemo.project;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 프로젝트 관리 저장소와 API가 공유하는 도메인 값 객체다. */
public final class ProjectModels {

    private ProjectModels() {
    }

    public enum ProjectStatus {
        PLANNED,
        ACTIVE,
        ON_HOLD,
        COMPLETED
    }

    public enum ProjectRole {
        OWNER,
        MANAGER,
        MEMBER,
        VIEWER
    }

    /** 프로젝트 참여 역할로 실행할 수 있는 프로젝트 내부 기능이다. */
    public enum ProjectCapability {
        VIEW,
        UPDATE,
        MANAGE_MEMBERS,
        TRANSFER_OWNERSHIP,
        ARCHIVE,
        RESTORE
    }

    /** 프로젝트 목록 정렬에 노출하는 안정적인 필드 집합이다. */
    public enum ProjectSort {
        ID,
        NAME,
        STATUS,
        START_DATE,
        END_DATE,
        OWNER
    }

    public enum SortDirection {
        ASC,
        DESC
    }

    public record Project(
            String id,
            String name,
            String description,
            ProjectStatus status,
            LocalDate startDate,
            LocalDate endDate,
            String ownerUsername,
            boolean active,
            long version) {
    }

    public record ProjectMember(
            String projectId,
            String username,
            ProjectRole role,
            boolean active) {
    }

    /** 검색·필터·정렬·페이징을 저장소 구현에 동일하게 전달하는 조회 계약이다. */
    public record ProjectSearchCriteria(
            String keyword,
            Set<ProjectStatus> statuses,
            String ownerUsername,
            Boolean active,
            ProjectSort sort,
            SortDirection direction,
            int page,
            int size) {

        public ProjectSearchCriteria {
            keyword = keyword == null ? "" : keyword.trim();
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            ownerUsername = ownerUsername == null ? "" : ownerUsername.trim();
            sort = sort == null ? ProjectSort.ID : sort;
            direction = direction == null ? SortDirection.ASC : direction;
            if (page < 0) {
                throw new IllegalArgumentException("페이지 번호는 0 이상이어야 합니다.");
            }
            if (size < 1 || size > 100) {
                throw new IllegalArgumentException("페이지 크기는 1~100이어야 합니다.");
            }
        }

        public static ProjectSearchCriteria all() {
            return new ProjectSearchCriteria(
                    "", Set.of(), "", null, ProjectSort.ID, SortDirection.ASC, 0, 100);
        }

        public String normalizedKeyword() {
            return keyword.toLowerCase(Locale.ROOT);
        }
    }

    /** 전체 건수와 페이지 메타데이터를 포함한 프로젝트 검색 결과다. */
    public record ProjectPage(
            List<Project> projects,
            long totalElements,
            int page,
            int size,
            int totalPages) {

        public ProjectPage {
            projects = List.copyOf(projects);
        }

        public static ProjectPage of(List<Project> projects, long totalElements, int page, int size) {
            int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
            return new ProjectPage(projects, totalElements, page, size, totalPages);
        }
    }

    /** 상태 전이 사유와 행위자를 보존하는 프로젝트 상태 이력이다. */
    public record ProjectStatusHistory(
            String projectId,
            long sequence,
            ProjectStatus fromStatus,
            ProjectStatus toStatus,
            String changedBy,
            String reason,
            Instant changedAt) {
    }

    /** 한 사용자의 현재 프로젝트 역할과 실행 가능 기능 평가 결과다. */
    public record ProjectAccess(
            String projectId,
            String username,
            ProjectRole role,
            Set<ProjectCapability> capabilities) {

        public ProjectAccess {
            capabilities = Set.copyOf(capabilities);
        }

        public boolean allows(ProjectCapability capability) {
            return capabilities.contains(capability);
        }
    }
}
