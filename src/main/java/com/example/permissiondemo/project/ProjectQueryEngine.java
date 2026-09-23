package com.example.permissiondemo.project;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 인메모리와 Oracle 저장소가 동일한 검색 의미를 공유하도록 하는 패키지 내부 조회기다. */
final class ProjectQueryEngine {

    private ProjectQueryEngine() {
    }

    static ProjectModels.ProjectPage search(
            List<ProjectModels.Project> source,
            ProjectModels.ProjectSearchCriteria criteria) {
        String keyword = criteria.normalizedKeyword();
        List<ProjectModels.Project> filtered = source.stream()
                .filter(project -> keyword.isEmpty() || contains(project.id(), keyword)
                        || contains(project.name(), keyword)
                        || contains(project.description(), keyword))
                .filter(project -> criteria.statuses().isEmpty()
                        || criteria.statuses().contains(project.status()))
                .filter(project -> criteria.ownerUsername().isEmpty()
                        || project.ownerUsername().equalsIgnoreCase(criteria.ownerUsername()))
                .filter(project -> criteria.active() == null
                        || project.active() == criteria.active())
                .sorted(comparator(criteria))
                .toList();

        long offset = (long) criteria.page() * criteria.size();
        int fromIndex = (int) Math.min(offset, filtered.size());
        int toIndex = Math.min(fromIndex + criteria.size(), filtered.size());
        return ProjectModels.ProjectPage.of(
                filtered.subList(fromIndex, toIndex),
                filtered.size(),
                criteria.page(),
                criteria.size());
    }

    private static boolean contains(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private static Comparator<ProjectModels.Project> comparator(
            ProjectModels.ProjectSearchCriteria criteria) {
        Comparator<ProjectModels.Project> comparator = switch (criteria.sort()) {
            case ID -> Comparator.comparing(ProjectModels.Project::id);
            case NAME -> Comparator.comparing(ProjectModels.Project::name);
            case STATUS -> Comparator.comparing(ProjectModels.Project::status);
            case START_DATE -> Comparator.comparing(ProjectModels.Project::startDate);
            case END_DATE -> Comparator.comparing(ProjectModels.Project::endDate);
            case OWNER -> Comparator.comparing(ProjectModels.Project::ownerUsername);
        };
        comparator = comparator.thenComparing(ProjectModels.Project::id);
        return criteria.direction() == ProjectModels.SortDirection.DESC
                ? comparator.reversed()
                : comparator;
    }
}
