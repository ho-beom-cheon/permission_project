package com.example.permissiondemo.project;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** DB 없이 실행하는 demo 프로필용 프로젝트 저장소다. */
@Repository
@Profile("demo")
public class InMemoryProjectRepository implements ProjectRepository {

    private final ConcurrentMap<String, ProjectModels.Project> projects = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ProjectModels.ProjectMember> members = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ProjectModels.ProjectStatusHistory>> statusHistories =
            new ConcurrentHashMap<>();

    public InMemoryProjectRepository() {
        saveSeed(new ProjectModels.Project(
                "PRJ_ACCESS_RENEWAL", "통합 권한 체계 고도화", "권한·메뉴·감사 체계를 운영 수준으로 개선",
                ProjectModels.ProjectStatus.ACTIVE, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 11, 30), "admin", true, 1));
        saveSeed(new ProjectModels.Project(
                "PRJ_DATA_HUB", "데이터 허브 구축", "업무 기준정보와 공통코드 통합",
                ProjectModels.ProjectStatus.PLANNED, LocalDate.of(2026, 10, 1),
                LocalDate.of(2027, 3, 31), "manager", true, 1));
        saveSeed(new ProjectModels.Project(
                "PRJ_MOBILE_PORTAL", "모바일 업무 포털", "핵심 업무의 모바일 접근성 개선",
                ProjectModels.ProjectStatus.ON_HOLD, LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 12, 31), "admin", true, 1));
        saveSeed(new ProjectModels.Project(
                "PRJ_AUDIT_AUTOMATION", "감사 증적 자동화", "권한 변경과 접근 기록의 정기 점검 자동화",
                ProjectModels.ProjectStatus.COMPLETED, LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 7, 31), "manager", true, 3));
        saveSeed(new ProjectModels.Project(
                "PRJ_CUSTOMER_INSIGHT", "고객 통합 분석", "채널별 고객 활동 지표와 분석 대시보드 구축",
                ProjectModels.ProjectStatus.ACTIVE, LocalDate.of(2026, 7, 1),
                LocalDate.of(2027, 1, 31), "admin", true, 2));
        saveSeed(new ProjectModels.Project(
                "PRJ_LEGACY_MIGRATION", "레거시 자료 이관", "기존 시스템 자료 이관이 완료된 보관 프로젝트",
                ProjectModels.ProjectStatus.COMPLETED, LocalDate.of(2025, 9, 1),
                LocalDate.of(2026, 4, 30), "manager", false, 3));
        saveSeed(new ProjectModels.Project(
                "PRJ_ZERO_TRUST", "제로 트러스트 전환", "관리 시스템 접근 경로와 단계별 인증 정책 재설계",
                ProjectModels.ProjectStatus.PLANNED, LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 4, 30), "authorityManager", true, 1));
        saveSeed(new ProjectModels.Project(
                "PRJ_PROGRAM_CATALOG", "업무 프로그램 카탈로그 정비", "메뉴·프로그램·기능 원장의 중복 제거와 책임자 정비",
                ProjectModels.ProjectStatus.ACTIVE, LocalDate.of(2026, 5, 15),
                LocalDate.of(2026, 10, 31), "programManager", true, 2));
        saveSeed(new ProjectModels.Project(
                "PRJ_PROJECT_PORTFOLIO", "전사 프로젝트 포트폴리오", "프로젝트 일정·책임자·참여 현황을 한 화면으로 통합",
                ProjectModels.ProjectStatus.ACTIVE, LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 12, 20), "projectManager", true, 2));
        saveSeed(new ProjectModels.Project(
                "PRJ_CODE_GOVERNANCE", "공통코드 거버넌스", "코드 소유 부서와 변경 승인 기준을 표준화",
                ProjectModels.ProjectStatus.ON_HOLD, LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 9, 30), "codeManager", true, 3));
        saveSeed(new ProjectModels.Project(
                "PRJ_REGIONAL_ROLLOUT", "지역 운영 표준 확산", "서울 운영 모델을 지역 조직에 단계적으로 적용",
                ProjectModels.ProjectStatus.PLANNED, LocalDate.of(2027, 1, 5),
                LocalDate.of(2027, 6, 30), "seoulOperator", true, 1));
        saveSeed(new ProjectModels.Project(
                "PRJ_DISASTER_RECOVERY", "업무 연속성 복구 체계", "장애 대응 절차와 복구 훈련을 완료한 보관 프로젝트",
                ProjectModels.ProjectStatus.COMPLETED, LocalDate.of(2025, 11, 1),
                LocalDate.of(2026, 3, 31), "backupAdmin", false, 4));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "admin", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "manager", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "authorityManager", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "auditor", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ACCESS_RENEWAL", "viewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DATA_HUB", "manager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DATA_HUB", "viewer", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DATA_HUB", "codeManager", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DATA_HUB", "contentEditor", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DATA_HUB", "busanViewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_MOBILE_PORTAL", "admin", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_MOBILE_PORTAL", "delegate", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_MOBILE_PORTAL", "programManager", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_MOBILE_PORTAL", "busanViewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_AUDIT_AUTOMATION", "manager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_AUDIT_AUTOMATION", "viewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_AUDIT_AUTOMATION", "auditor", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_AUDIT_AUTOMATION", "delegate", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CUSTOMER_INSIGHT", "admin", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CUSTOMER_INSIGHT", "manager", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CUSTOMER_INSIGHT", "delegate", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CUSTOMER_INSIGHT", "busanViewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_LEGACY_MIGRATION", "manager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_LEGACY_MIGRATION", "backupAdmin", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_LEGACY_MIGRATION", "contentEditor", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_LEGACY_MIGRATION", "auditor", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ZERO_TRUST", "authorityManager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ZERO_TRUST", "admin", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ZERO_TRUST", "auditor", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_ZERO_TRUST", "viewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROGRAM_CATALOG", "programManager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROGRAM_CATALOG", "contentEditor", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROGRAM_CATALOG", "codeManager", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROGRAM_CATALOG", "auditor", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROJECT_PORTFOLIO", "projectManager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROJECT_PORTFOLIO", "seoulOperator", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROJECT_PORTFOLIO", "programManager", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_PROJECT_PORTFOLIO", "busanViewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CODE_GOVERNANCE", "codeManager", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CODE_GOVERNANCE", "programManager", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CODE_GOVERNANCE", "authorityManager", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_CODE_GOVERNANCE", "auditor", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_REGIONAL_ROLLOUT", "seoulOperator", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_REGIONAL_ROLLOUT", "projectManager", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_REGIONAL_ROLLOUT", "contentEditor", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_REGIONAL_ROLLOUT", "busanViewer", ProjectModels.ProjectRole.VIEWER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DISASTER_RECOVERY", "backupAdmin", ProjectModels.ProjectRole.OWNER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DISASTER_RECOVERY", "admin", ProjectModels.ProjectRole.MANAGER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DISASTER_RECOVERY", "codeManager", ProjectModels.ProjectRole.MEMBER, true));
        saveMemberSeed(new ProjectModels.ProjectMember(
                "PRJ_DISASTER_RECOVERY", "auditor", ProjectModels.ProjectRole.VIEWER, true));

        projects.values().forEach(project -> saveStatusHistorySeed(
                project.id(), 1, null, ProjectModels.ProjectStatus.PLANNED,
                "사업 범위와 일정 계획 승인", project.startDate().minusDays(14)));
        saveStatusHistorySeed(
                "PRJ_ACCESS_RENEWAL", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "관리 권한 체계 개선 착수 승인",
                LocalDate.of(2026, 8, 1));
        saveStatusHistorySeed(
                "PRJ_CUSTOMER_INSIGHT", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "분석 범위와 지표 정의 확정",
                LocalDate.of(2026, 7, 1));
        saveStatusHistorySeed(
                "PRJ_MOBILE_PORTAL", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "현장 사용자 요구사항 확정 후 개발 착수",
                LocalDate.of(2026, 6, 1));
        saveStatusHistorySeed(
                "PRJ_MOBILE_PORTAL", 3, ProjectModels.ProjectStatus.ACTIVE,
                ProjectModels.ProjectStatus.ON_HOLD, "외부 인증 정책 확정 대기",
                LocalDate.of(2026, 8, 15));
        saveStatusHistorySeed(
                "PRJ_AUDIT_AUTOMATION", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "감사 통제 항목 확정 후 개발 착수",
                LocalDate.of(2026, 1, 15));
        saveStatusHistorySeed(
                "PRJ_AUDIT_AUTOMATION", 3, ProjectModels.ProjectStatus.ACTIVE,
                ProjectModels.ProjectStatus.COMPLETED, "정기 점검 자동화 운영 이관 완료",
                LocalDate.of(2026, 7, 31));
        saveStatusHistorySeed(
                "PRJ_LEGACY_MIGRATION", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "이관 대상과 검증 기준 확정",
                LocalDate.of(2025, 9, 1));
        saveStatusHistorySeed(
                "PRJ_LEGACY_MIGRATION", 3, ProjectModels.ProjectStatus.ACTIVE,
                ProjectModels.ProjectStatus.COMPLETED, "데이터 대사와 사용자 검증 완료",
                LocalDate.of(2026, 4, 30));
        saveStatusHistorySeed(
                "PRJ_PROGRAM_CATALOG", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "중복 기능 식별과 책임 부서 협의 완료",
                LocalDate.of(2026, 5, 15));
        saveStatusHistorySeed(
                "PRJ_PROJECT_PORTFOLIO", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "포트폴리오 운영 지표 확정",
                LocalDate.of(2026, 7, 15));
        saveStatusHistorySeed(
                "PRJ_CODE_GOVERNANCE", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "공통코드 현황 조사 완료",
                LocalDate.of(2026, 4, 1));
        saveStatusHistorySeed(
                "PRJ_CODE_GOVERNANCE", 3, ProjectModels.ProjectStatus.ACTIVE,
                ProjectModels.ProjectStatus.ON_HOLD, "부서별 코드 소유권 조정 대기",
                LocalDate.of(2026, 7, 20));
        saveStatusHistorySeed(
                "PRJ_DISASTER_RECOVERY", 2, ProjectModels.ProjectStatus.PLANNED,
                ProjectModels.ProjectStatus.ACTIVE, "복구 목표와 모의훈련 범위 확정",
                LocalDate.of(2025, 11, 1));
        saveStatusHistorySeed(
                "PRJ_DISASTER_RECOVERY", 3, ProjectModels.ProjectStatus.ACTIVE,
                ProjectModels.ProjectStatus.COMPLETED, "복구 훈련과 개선 조치 검증 완료",
                LocalDate.of(2026, 3, 31));
    }

    @Override
    public List<ProjectModels.Project> findAll() {
        return projects.values().stream()
                .sorted(Comparator.comparing(ProjectModels.Project::id))
                .toList();
    }

    @Override
    public ProjectModels.ProjectPage search(ProjectModels.ProjectSearchCriteria criteria) {
        return ProjectQueryEngine.search(new ArrayList<>(projects.values()), criteria);
    }

    @Override
    public Optional<ProjectModels.Project> findById(String projectId) {
        return Optional.ofNullable(projects.get(projectId));
    }

    @Override
    public synchronized ProjectModels.Project save(ProjectModels.Project project) {
        return saveLocked(project);
    }

    @Override
    public synchronized ProjectModels.Project transferOwnership(
            ProjectModels.Project project,
            String previousOwnerUsername) {
        ProjectModels.Project saved = saveLocked(project);
        members.values().stream()
                .filter(member -> member.projectId().equals(saved.id()))
                .filter(member -> member.role() == ProjectModels.ProjectRole.OWNER)
                .filter(member -> !member.username().equals(saved.ownerUsername()))
                .map(ProjectModels.ProjectMember::username)
                .toList()
                .forEach(username -> members.put(
                        key(saved.id(), username),
                        new ProjectModels.ProjectMember(
                                saved.id(), username, ProjectModels.ProjectRole.MANAGER, true)));
        if (!previousOwnerUsername.equals(saved.ownerUsername())) {
            members.put(
                    key(saved.id(), previousOwnerUsername),
                    new ProjectModels.ProjectMember(
                            saved.id(), previousOwnerUsername, ProjectModels.ProjectRole.MANAGER, true));
        }
        members.put(
                key(saved.id(), saved.ownerUsername()),
                new ProjectModels.ProjectMember(
                        saved.id(), saved.ownerUsername(), ProjectModels.ProjectRole.OWNER, true));
        return saved;
    }

    private ProjectModels.Project saveLocked(ProjectModels.Project project) {
        ProjectModels.Project current = projects.get(project.id());
        if (current != null && current.version() != project.version()) {
            throw new ApiException(ErrorCode.CONFLICT, project.id());
        }
        long nextVersion = current == null ? 1 : current.version() + 1;
        ProjectModels.Project saved = new ProjectModels.Project(
                project.id(), project.name(), project.description(), project.status(),
                project.startDate(), project.endDate(), project.ownerUsername(),
                project.active(), nextVersion);
        projects.put(saved.id(), saved);
        return saved;
    }

    @Override
    public List<ProjectModels.ProjectMember> findMembers(String projectId) {
        return members.values().stream()
                .filter(member -> member.projectId().equals(projectId))
                .sorted(Comparator.comparing(ProjectModels.ProjectMember::username))
                .toList();
    }

    @Override
    public synchronized ProjectModels.ProjectMember saveMember(ProjectModels.ProjectMember member) {
        ProjectModels.Project project = projects.get(member.projectId());
        if (project == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, member.projectId());
        }
        if (project.ownerUsername().equals(member.username())
                && (member.role() != ProjectModels.ProjectRole.OWNER || !member.active())) {
            throw new ApiException(ErrorCode.CONFLICT, member.username());
        }
        if (member.role() == ProjectModels.ProjectRole.OWNER
                && !project.ownerUsername().equals(member.username())) {
            throw new ApiException(ErrorCode.CONFLICT, member.username());
        }
        members.put(key(member.projectId(), member.username()), member);
        return member;
    }

    @Override
    public synchronized void deleteMember(String projectId, String username) {
        ProjectModels.Project project = projects.get(projectId);
        if (project == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, projectId);
        }
        if (project.ownerUsername().equals(username)) {
            throw new ApiException(ErrorCode.CONFLICT, username);
        }
        ProjectModels.ProjectMember removed = members.remove(key(projectId, username));
        if (removed == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username);
        }
    }

    @Override
    public synchronized List<ProjectModels.ProjectStatusHistory> findStatusHistory(String projectId) {
        return statusHistories.getOrDefault(projectId, List.of()).stream()
                .sorted(Comparator.comparingLong(ProjectModels.ProjectStatusHistory::sequence).reversed())
                .toList();
    }

    @Override
    public synchronized ProjectModels.ProjectStatusHistory appendStatusHistory(
            ProjectModels.ProjectStatusHistory history) {
        if (!projects.containsKey(history.projectId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, history.projectId());
        }
        List<ProjectModels.ProjectStatusHistory> histories = statusHistories.computeIfAbsent(
                history.projectId(), ignored -> new ArrayList<>());
        long nextSequence = histories.stream()
                .mapToLong(ProjectModels.ProjectStatusHistory::sequence)
                .max()
                .orElse(0L) + 1L;
        ProjectModels.ProjectStatusHistory saved = new ProjectModels.ProjectStatusHistory(
                history.projectId(), nextSequence, history.fromStatus(), history.toStatus(),
                history.changedBy(), history.reason(), history.changedAt());
        histories.add(saved);
        return saved;
    }

    private void saveSeed(ProjectModels.Project project) {
        projects.put(project.id(), project);
    }

    private void saveMemberSeed(ProjectModels.ProjectMember member) {
        members.put(key(member.projectId(), member.username()), member);
    }

    private void saveStatusHistorySeed(
            String projectId,
            long sequence,
            ProjectModels.ProjectStatus fromStatus,
            ProjectModels.ProjectStatus toStatus,
            String reason,
            LocalDate changedDate) {
        statusHistories.computeIfAbsent(projectId, ignored -> new ArrayList<>())
                .add(new ProjectModels.ProjectStatusHistory(
                        projectId,
                        sequence,
                        fromStatus,
                        toStatus,
                        "SYSTEM",
                        reason,
                        changedDate.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    private String key(String projectId, String username) {
        return projectId + ":" + username;
    }
}
