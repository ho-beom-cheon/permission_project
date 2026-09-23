package com.example.permissiondemo.project;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.authorization.EffectiveAuthorityService;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로젝트 마스터·참여자·상태 이력의 검증, 저장, 역할 인가와 감사를 조정한다. */
@Service
public class ProjectService {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Z0-9_]{1,50}");
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final ProjectRepository repository;
    private final AuthorizationCatalog authorizationCatalog;
    private final AuditEventService auditEventService;
    private final Clock clock;
    private final EffectiveAuthorityService effectiveAuthorityService;

    /** Spring 없이 구성하는 기존 단위 테스트와 호출자의 호환성을 유지한다. */
    public ProjectService(
            ProjectRepository repository,
            AuthorizationCatalog authorizationCatalog,
            AuditEventService auditEventService) {
        this(
                repository,
                authorizationCatalog,
                auditEventService,
                Clock.systemUTC(),
                new EffectiveAuthorityService(authorizationCatalog, Clock.systemUTC()));
    }

    /** 고정 Clock을 사용하는 단위 테스트 호환 생성자다. */
    public ProjectService(
            ProjectRepository repository,
            AuthorizationCatalog authorizationCatalog,
            AuditEventService auditEventService,
            Clock clock) {
        this(
                repository,
                authorizationCatalog,
                auditEventService,
                clock,
                new EffectiveAuthorityService(authorizationCatalog, clock));
    }

    @Autowired
    public ProjectService(
            ProjectRepository repository,
            AuthorizationCatalog authorizationCatalog,
            AuditEventService auditEventService,
            Clock clock,
            EffectiveAuthorityService effectiveAuthorityService) {
        this.repository = repository;
        this.authorizationCatalog = authorizationCatalog;
        this.auditEventService = auditEventService;
        this.clock = clock;
        this.effectiveAuthorityService = effectiveAuthorityService;
    }

    public ProjectAdminView loadProjects() {
        List<ProjectDetail> projects = repository.findAll().stream()
                .map(this::detail)
                .toList();
        return new ProjectAdminView(projects, authorizationCatalog.users());
    }

    /** 조건 검색과 페이지 메타데이터를 포함한 관리 화면용 프로젝트 목록을 반환한다. */
    public ProjectSearchView searchProjects(ProjectModels.ProjectSearchCriteria criteria) {
        if (criteria == null) {
            throw new IllegalArgumentException("검색 조건이 필요합니다.");
        }
        ProjectModels.ProjectPage page = repository.search(criteria);
        return new ProjectSearchView(
                page.projects().stream().map(this::detail).toList(),
                page.totalElements(), page.page(), page.size(), page.totalPages());
    }

    public ProjectDetail getProject(String projectId) {
        return detail(requireProject(projectId));
    }

    @Transactional
    public ProjectModels.Project saveProject(ProjectModels.Project request) {
        return saveProject(request, SYSTEM_ACTOR, "프로젝트 관리 변경");
    }

    /** 상태 이력 행위자와 변경 사유를 명시해 프로젝트를 등록하거나 수정한다. */
    @Transactional
    public ProjectModels.Project saveProject(
            ProjectModels.Project request,
            String actor,
            String statusChangeReason) {
        if (request == null) {
            throw new IllegalArgumentException("프로젝트 정보가 필요합니다.");
        }
        String projectId = normalizeId(request.id());
        String name = requiredText(request.name(), 100, "프로젝트명");
        String description = optionalText(request.description(), 500, "설명");
        String ownerUsername = requiredText(request.ownerUsername(), 100, "오너");
        validateUser(ownerUsername);
        validatePeriod(request.startDate(), request.endDate());
        validateStatus(request.status());
        if (request.version() < 0) {
            throw new IllegalArgumentException("버전은 0 이상이어야 합니다.");
        }

        Optional<ProjectModels.Project> existing = repository.findById(projectId);
        String normalizedActor = normalizeActor(actor);
        existing.ifPresent(current -> {
            ensureMutable(current);
            requireExpectedVersion(current, request.version());
            if (current.active() != request.active()) {
                throw new ApiException(ErrorCode.CONFLICT, "보관·복원은 전용 기능을 사용해야 합니다.");
            }
            requireCapability(current.id(), normalizedActor, ProjectModels.ProjectCapability.UPDATE);
            validateStatusTransition(current.status(), request.status());
        });
        boolean statusChanged = existing.isEmpty()
                || existing.orElseThrow().status() != request.status();
        // 인메모리 저장소도 오류 응답에 부분 저장이 남지 않도록 모든 입력 검증을 변경 전에 끝낸다.
        String historyReason = statusChanged
                ? requiredText(existing.isEmpty() ? "프로젝트 초기 등록" : statusChangeReason, 300, "상태 변경 사유")
                : null;
        ProjectModels.Project normalized = new ProjectModels.Project(
                projectId, name, description, request.status(), request.startDate(), request.endDate(),
                ownerUsername, request.active(), request.version());
        boolean ownerChanged = existing
                .map(current -> !current.ownerUsername().equals(ownerUsername))
                .orElse(false);
        if (ownerChanged) {
            requireCapability(projectId, normalizedActor, ProjectModels.ProjectCapability.TRANSFER_OWNERSHIP);
        }

        ProjectModels.Project saved;
        if (ownerChanged) {
            saved = repository.transferOwnership(normalized, existing.orElseThrow().ownerUsername());
        } else {
            saved = repository.save(normalized);
            repository.saveMember(new ProjectModels.ProjectMember(
                    saved.id(), saved.ownerUsername(), ProjectModels.ProjectRole.OWNER, true));
        }

        if (statusChanged) {
            appendStatusHistory(
                    saved,
                    existing.map(ProjectModels.Project::status).orElse(null),
                    normalizedActor,
                    historyReason);
        }
        auditProjectSave(existing.orElse(null), saved, ownerChanged);
        return saved;
    }

    /** 명시적 사유와 버전으로 오너를 이전하며 기존 오너는 활성 MANAGER로 유지한다. */
    @Transactional
    public ProjectModels.Project transferOwnership(
            String projectId,
            String newOwnerUsername,
            long expectedVersion,
            String actor,
            String reason) {
        ProjectModels.Project current = requireProject(projectId);
        ensureMutable(current);
        requireExpectedVersion(current, expectedVersion);
        String newOwner = requiredText(newOwnerUsername, 100, "신규 오너");
        validateUser(newOwner);
        String normalizedActor = normalizeActor(actor);
        requireCapability(current.id(), normalizedActor, ProjectModels.ProjectCapability.TRANSFER_OWNERSHIP);
        String normalizedReason = requiredText(reason, 300, "오너 이전 사유");
        if (current.ownerUsername().equals(newOwner)) {
            throw new ApiException(ErrorCode.CONFLICT, newOwner);
        }

        ProjectModels.Project saved = repository.transferOwnership(new ProjectModels.Project(
                current.id(), current.name(), current.description(), current.status(),
                current.startDate(), current.endDate(), newOwner, current.active(), current.version()),
                current.ownerUsername());
        auditEventService.record(
                "PROJECT_OWNER_TRANSFERRED", "PROJECT", saved.id(), "SUCCESS",
                Map.of(
                        "fromOwner", current.ownerUsername(),
                        "toOwner", saved.ownerUsername(),
                        "reason", normalizedReason,
                        "version", saved.version()));
        return saved;
    }

    /** 삭제 대신 활성 플래그를 내리고 사유와 변경 전후 버전을 감사에 남긴다. */
    @Transactional
    public ProjectModels.Project archiveProject(
            String projectId,
            long expectedVersion,
            String actor,
            String reason) {
        ProjectModels.Project current = requireProject(projectId);
        requireExpectedVersion(current, expectedVersion);
        if (!current.active()) {
            throw new ApiException(ErrorCode.CONFLICT, current.id());
        }
        requireCapability(current.id(), normalizeActor(actor), ProjectModels.ProjectCapability.ARCHIVE);
        String normalizedReason = requiredText(reason, 300, "보관 사유");
        ProjectModels.Project saved = repository.save(copyWithActive(current, false));
        auditLifecycle("PROJECT_ARCHIVED", current, saved, normalizedReason);
        return saved;
    }

    /** 보관 프로젝트는 오너만 기대 버전과 사유를 제출해 복원할 수 있다. */
    @Transactional
    public ProjectModels.Project restoreProject(
            String projectId,
            long expectedVersion,
            String actor,
            String reason) {
        ProjectModels.Project current = requireProject(projectId);
        requireExpectedVersion(current, expectedVersion);
        if (current.active()) {
            throw new ApiException(ErrorCode.CONFLICT, current.id());
        }
        requireCapability(current.id(), normalizeActor(actor), ProjectModels.ProjectCapability.RESTORE);
        String normalizedReason = requiredText(reason, 300, "복원 사유");
        ProjectModels.Project saved = repository.save(copyWithActive(current, true));
        auditLifecycle("PROJECT_RESTORED", current, saved, normalizedReason);
        return saved;
    }

    @Transactional
    public ProjectModels.ProjectMember saveMember(ProjectModels.ProjectMember request) {
        return saveMemberInternal(request, null);
    }

    /** 프로젝트 역할로 참여자를 관리하는 호출 경로다. */
    @Transactional
    public ProjectModels.ProjectMember saveMemberAs(
            String actor,
            ProjectModels.ProjectMember request) {
        return saveMemberInternal(request, normalizeActor(actor));
    }

    private ProjectModels.ProjectMember saveMemberInternal(
            ProjectModels.ProjectMember request,
            String actor) {
        if (request == null || request.role() == null) {
            throw new IllegalArgumentException("참여자 역할 정보가 필요합니다.");
        }
        ProjectModels.Project project = requireProject(request.projectId());
        ensureMutable(project);
        if (actor != null) {
            requireCapability(project.id(), actor, ProjectModels.ProjectCapability.MANAGE_MEMBERS);
        }
        String username = requiredText(request.username(), 100, "참여자");
        validateUser(username);
        if (username.equals(project.ownerUsername())
                && (request.role() != ProjectModels.ProjectRole.OWNER || !request.active())) {
            throw new ApiException(ErrorCode.CONFLICT, username);
        }
        if (request.role() == ProjectModels.ProjectRole.OWNER
                && !username.equals(project.ownerUsername())) {
            throw new ApiException(ErrorCode.CONFLICT, username);
        }

        ProjectModels.ProjectMember normalized = new ProjectModels.ProjectMember(
                project.id(), username, request.role(), request.active());
        ProjectModels.ProjectMember before = findMember(project.id(), username).orElse(null);
        ProjectModels.ProjectMember saved = repository.saveMember(normalized);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("beforeRole", before == null ? "" : before.role().name());
        details.put("afterRole", saved.role().name());
        details.put("beforeActive", before != null && before.active());
        details.put("afterActive", saved.active());
        auditEventService.record(
                "PROJECT_MEMBER_SAVED", "PROJECT_MEMBER",
                saved.projectId() + ":" + saved.username(), "SUCCESS", details);
        return saved;
    }

    @Transactional
    public void removeMember(String projectId, String username) {
        removeMemberInternal(projectId, username, null);
    }

    @Transactional
    public void removeMemberAs(String actor, String projectId, String username) {
        removeMemberInternal(projectId, username, normalizeActor(actor));
    }

    private void removeMemberInternal(String projectId, String username, String actor) {
        ProjectModels.Project project = requireProject(projectId);
        ensureMutable(project);
        if (actor != null) {
            requireCapability(project.id(), actor, ProjectModels.ProjectCapability.MANAGE_MEMBERS);
        }
        String normalizedUsername = requiredText(username, 100, "참여자");
        if (project.ownerUsername().equals(normalizedUsername)) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedUsername);
        }
        ProjectModels.ProjectMember before = findMember(project.id(), normalizedUsername)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedUsername));
        repository.deleteMember(project.id(), normalizedUsername);
        auditEventService.record(
                "PROJECT_MEMBER_REMOVED", "PROJECT_MEMBER",
                project.id() + ":" + normalizedUsername, "SUCCESS",
                Map.of("role", before.role().name(), "active", before.active()));
    }

    /** 현재 프로젝트 상태와 활성 참여 역할을 함께 반영해 실행 가능 기능을 계산한다. */
    public ProjectModels.ProjectAccess evaluateAccess(String projectId, String username) {
        ProjectModels.Project project = requireProject(projectId);
        String normalizedUsername = requiredText(username, 100, "사용자");
        Set<String> globalAuthorities = effectiveAuthorityService.findEffectiveAuthorityIds(
                normalizedUsername);
        boolean globalManager = globalAuthorities.contains(AuthorizationCatalog.AUTH_SYSTEM_ADMIN)
                || globalAuthorities.contains(AuthorizationCatalog.AUTH_PROJECT_MANAGER);
        Optional<ProjectModels.ProjectMember> membership = findMember(project.id(), normalizedUsername)
                .filter(ProjectModels.ProjectMember::active);
        if (!globalManager && membership.isEmpty()) {
            return new ProjectModels.ProjectAccess(project.id(), normalizedUsername, null, Set.of());
        }

        ProjectModels.ProjectRole role = membership.map(ProjectModels.ProjectMember::role).orElse(null);
        EnumSet<ProjectModels.ProjectCapability> capabilities = globalManager
                ? EnumSet.allOf(ProjectModels.ProjectCapability.class)
                : capabilities(role);
        if (!project.active()) {
            capabilities.retainAll(globalManager || role == ProjectModels.ProjectRole.OWNER
                    ? Set.of(ProjectModels.ProjectCapability.VIEW, ProjectModels.ProjectCapability.RESTORE)
                    : Set.of(ProjectModels.ProjectCapability.VIEW));
        } else if (project.status() == ProjectModels.ProjectStatus.COMPLETED) {
            capabilities.retainAll(globalManager || role == ProjectModels.ProjectRole.OWNER
                    ? Set.of(ProjectModels.ProjectCapability.VIEW, ProjectModels.ProjectCapability.ARCHIVE)
                    : Set.of(ProjectModels.ProjectCapability.VIEW));
        }
        return new ProjectModels.ProjectAccess(project.id(), normalizedUsername, role, capabilities);
    }

    public boolean isAllowed(
            String projectId,
            String username,
            ProjectModels.ProjectCapability capability) {
        return capability != null && evaluateAccess(projectId, username).allows(capability);
    }

    public List<ProjectModels.ProjectStatusHistory> statusHistory(String projectId) {
        ProjectModels.Project project = requireProject(projectId);
        return repository.findStatusHistory(project.id());
    }

    private ProjectDetail detail(ProjectModels.Project project) {
        return new ProjectDetail(
                project,
                repository.findMembers(project.id()),
                repository.findStatusHistory(project.id()));
    }

    private Optional<ProjectModels.ProjectMember> findMember(String projectId, String username) {
        return repository.findMembers(projectId).stream()
                .filter(member -> member.username().equals(username))
                .findFirst();
    }

    private void requireCapability(
            String projectId,
            String actor,
            ProjectModels.ProjectCapability capability) {
        if (SYSTEM_ACTOR.equals(actor)) {
            return;
        }
        if (!isAllowed(projectId, actor, capability)) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, actor, projectId, capability.name());
        }
    }

    private EnumSet<ProjectModels.ProjectCapability> capabilities(ProjectModels.ProjectRole role) {
        return switch (role) {
            case OWNER -> EnumSet.allOf(ProjectModels.ProjectCapability.class);
            case MANAGER -> EnumSet.of(
                    ProjectModels.ProjectCapability.VIEW,
                    ProjectModels.ProjectCapability.UPDATE,
                    ProjectModels.ProjectCapability.MANAGE_MEMBERS);
            case MEMBER, VIEWER -> EnumSet.of(ProjectModels.ProjectCapability.VIEW);
        };
    }

    private ProjectModels.Project requireProject(String projectId) {
        String normalizedId = normalizeId(projectId);
        return repository.findById(normalizedId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedId));
    }

    private void appendStatusHistory(
            ProjectModels.Project project,
            ProjectModels.ProjectStatus fromStatus,
            String actor,
            String reason) {
        repository.appendStatusHistory(new ProjectModels.ProjectStatusHistory(
                project.id(), 0, fromStatus, project.status(), normalizeActor(actor),
                requiredText(reason, 300, "상태 변경 사유"), Instant.now(clock)));
    }

    private void auditProjectSave(
            ProjectModels.Project before,
            ProjectModels.Project after,
            boolean ownerChanged) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("beforeStatus", before == null ? "" : before.status().name());
        details.put("afterStatus", after.status().name());
        details.put("beforeOwner", before == null ? "" : before.ownerUsername());
        details.put("afterOwner", after.ownerUsername());
        details.put("ownerChanged", ownerChanged);
        details.put("beforeActive", before != null && before.active());
        details.put("afterActive", after.active());
        details.put("version", after.version());
        auditEventService.record(
                before == null ? "PROJECT_CREATED" : "PROJECT_UPDATED",
                "PROJECT", after.id(), "SUCCESS", details);
    }

    private void auditLifecycle(
            String eventType,
            ProjectModels.Project before,
            ProjectModels.Project after,
            String reason) {
        auditEventService.record(
                eventType, "PROJECT", after.id(), "SUCCESS",
                Map.of(
                        "beforeActive", before.active(),
                        "afterActive", after.active(),
                        "reason", reason,
                        "beforeVersion", before.version(),
                        "afterVersion", after.version()));
    }

    private ProjectModels.Project copyWithActive(ProjectModels.Project project, boolean active) {
        return new ProjectModels.Project(
                project.id(), project.name(), project.description(), project.status(),
                project.startDate(), project.endDate(), project.ownerUsername(), active, project.version());
    }

    private void ensureMutable(ProjectModels.Project project) {
        if (!project.active() || project.status() == ProjectModels.ProjectStatus.COMPLETED) {
            throw new ApiException(ErrorCode.CONFLICT, project.id());
        }
    }

    private void requireExpectedVersion(ProjectModels.Project project, long expectedVersion) {
        if (expectedVersion < 1 || project.version() != expectedVersion) {
            throw new ApiException(ErrorCode.CONFLICT, project.id());
        }
    }

    private void validateUser(String username) {
        if (authorizationCatalog.findUser(username).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username);
        }
    }

    private String normalizeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return SYSTEM_ACTOR;
        }
        String normalized = actor.trim();
        if (!SYSTEM_ACTOR.equals(normalized)) {
            validateUser(normalized);
        }
        return normalized;
    }

    private String normalizeId(String value) {
        if (value == null || !ID_PATTERN.matcher(value.trim().toUpperCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("projectId 형식이 올바르지 않습니다.");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + "은(는) 1~" + max + "자여야 합니다.");
        }
        return value.trim();
    }

    private String optionalText(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.trim().length() > max) {
            throw new IllegalArgumentException(field + "은(는) " + max + "자 이하여야 합니다.");
        }
        return value.trim();
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("프로젝트 기간을 확인해 주세요.");
        }
    }

    private void validateStatus(ProjectModels.ProjectStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("프로젝트 상태가 필요합니다.");
        }
    }

    private void validateStatusTransition(
            ProjectModels.ProjectStatus from,
            ProjectModels.ProjectStatus to) {
        if (from == to) {
            return;
        }
        boolean allowed = switch (from) {
            case PLANNED -> to == ProjectModels.ProjectStatus.ACTIVE
                    || to == ProjectModels.ProjectStatus.ON_HOLD;
            case ACTIVE -> to == ProjectModels.ProjectStatus.ON_HOLD
                    || to == ProjectModels.ProjectStatus.COMPLETED;
            case ON_HOLD -> to == ProjectModels.ProjectStatus.ACTIVE
                    || to == ProjectModels.ProjectStatus.COMPLETED;
            case COMPLETED -> false;
        };
        if (!allowed) {
            throw new ApiException(ErrorCode.CONFLICT, from.name(), to.name());
        }
    }

    public record ProjectAdminView(
            List<ProjectDetail> projects,
            List<AuthorizationCatalog.UserProfile> users) {
    }

    public record ProjectSearchView(
            List<ProjectDetail> projects,
            long totalElements,
            int page,
            int size,
            int totalPages) {

        public ProjectSearchView {
            projects = List.copyOf(projects);
        }
    }

    public record ProjectDetail(
            ProjectModels.Project project,
            List<ProjectModels.ProjectMember> members,
            List<ProjectModels.ProjectStatusHistory> statusHistory) {

        public ProjectDetail {
            members = List.copyOf(members);
            statusHistory = List.copyOf(statusHistory);
        }
    }
}
