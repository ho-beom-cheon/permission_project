package com.example.permissiondemo.authorization;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.stereotype.Component;

/**
 * 권한 마스터, 사용자 권한 이력, 메뉴와 프로그램 기능 매핑을 한곳에 보관하는 데모 카탈로그다.
 *
 * <p>이 클래스의 실행 상태는 StateCoordinator를 통해 별도 로컬 DB에 저장·복원된다. 원본 스키마 이관 시에는
 * 권한 마스터, 사용자-권한 이력, 권한-메뉴, 권한-기능 Repository로 각각 교체하되 서비스가
 * 사용하는 조회 계약은 유지하는 것을 전제로 한다.</p>
 */
@Component
@com.example.permissiondemo.storage.StateBoundary
public class AuthorizationCatalog implements com.example.permissiondemo.storage.StateParticipant {

    public static final String AUTH_SYSTEM_ADMIN = "AUTH_SYSTEM_ADMIN";
    public static final String AUTH_CONTENT_MANAGER = "AUTH_CONTENT_MANAGER";
    public static final String AUTH_VIEWER = "AUTH_VIEWER";

    private static final LocalDate LONG_AGO = LocalDate.of(2020, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Z0-9_]{1,50}");

    /** 사용 가능 여부를 포함한 권한 마스터다. 관리 화면 저장을 반영할 수 있도록 동시성 맵에 보관한다. */
    private final ConcurrentMap<String, AuthorityDefinition> authorities = new ConcurrentHashMap<>();

    /** 원본 권한분류기본의 계층을 보관한다. 분류는 그 자체로 사용자에게 부여되지 않는다. */
    private final ConcurrentMap<String, AuthorityClassification> classifications = new ConcurrentHashMap<>();

    /** 사용자의 현재 소속 조직이다. 권한 이력의 조직과 일치하는 경우에만 현재 권한으로 평가한다. */
    private final Map<String, UserProfile> users = new ConcurrentHashMap<>(Map.ofEntries(
            Map.entry("admin", new UserProfile("admin", "HQ")),
            Map.entry("manager", new UserProfile("manager", "HQ")),
            Map.entry("viewer", new UserProfile("viewer", "HQ")),
            Map.entry("delegate", new UserProfile("delegate", "HQ")),
            Map.entry("expired", new UserProfile("expired", "HQ")),
            Map.entry("revoked", new UserProfile("revoked", "HQ")),
            Map.entry("moved", new UserProfile("moved", "BRANCH_B")),
            Map.entry("revokedManager", new UserProfile("revokedManager", "HQ")),
            Map.entry("orphanDelegate", new UserProfile("orphanDelegate", "HQ"))));

    /**
     * 직접·위임 권한의 이력 데이터다.
     * 같은 사용자·조직·권한·부여유형은 sequence가 가장 큰 행을 최신 상태로 본다.
     */
    private final List<AuthorityAssignment> assignments = new CopyOnWriteArrayList<>(List.of(
            direct("admin", AUTH_SYSTEM_ADMIN),
            direct("manager", AUTH_CONTENT_MANAGER),
            direct("viewer", AUTH_VIEWER),
            direct("delegate", AUTH_VIEWER),
            delegated("delegate", AUTH_CONTENT_MANAGER, "manager", LONG_AGO, FAR_FUTURE),
            direct("expired", AUTH_VIEWER),
            delegated("expired", AUTH_SYSTEM_ADMIN, "admin",
                    LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31)),
            new AuthorityAssignment("viewer", "HQ", AUTH_CONTENT_MANAGER, AssignmentType.DELEGATED,
                    AssignmentStatus.PENDING, LONG_AGO, FAR_FUTURE, "manager", 1),
            new AuthorityAssignment("admin", "HQ", "AUTH_DISABLED", AssignmentType.DIRECT,
                    AssignmentStatus.APPROVED, LONG_AGO, FAR_FUTURE, null, 1),
            new AuthorityAssignment("revoked", "HQ", AUTH_SYSTEM_ADMIN, AssignmentType.DIRECT,
                    AssignmentStatus.APPROVED, LONG_AGO, FAR_FUTURE, null, 1),
            new AuthorityAssignment("revoked", "HQ", AUTH_SYSTEM_ADMIN, AssignmentType.DIRECT,
                    AssignmentStatus.REVOKED, LONG_AGO, FAR_FUTURE, null, 2),
            new AuthorityAssignment("moved", "BRANCH_A", AUTH_SYSTEM_ADMIN, AssignmentType.DIRECT,
                    AssignmentStatus.APPROVED, LONG_AGO, FAR_FUTURE, null, 1),
            new AuthorityAssignment("revokedManager", "HQ", AUTH_CONTENT_MANAGER, AssignmentType.DIRECT,
                    AssignmentStatus.APPROVED, LONG_AGO, FAR_FUTURE, null, 1),
            new AuthorityAssignment("revokedManager", "HQ", AUTH_CONTENT_MANAGER, AssignmentType.DIRECT,
                    AssignmentStatus.REVOKED, LONG_AGO, FAR_FUTURE, null, 2),
            direct("orphanDelegate", AUTH_VIEWER),
            delegated("orphanDelegate", AUTH_CONTENT_MANAGER, "revokedManager", LONG_AGO, FAR_FUTURE)));

    /** 메뉴 계층, 화면 경로, 노출 여부와 공용 메뉴 여부를 정의한다. */
    private final ConcurrentMap<String, MenuDefinition> menus = new ConcurrentHashMap<>();

    /**
     * 권한별 직접 접근 메뉴다.
     * 상위 컨테이너는 저장하지 않아도 MenuAuthorizationService가 허용된 자식의 조상을 보존한다.
     */
    private final ConcurrentMap<String, Set<String>> authorityMenuGrants = new ConcurrentHashMap<>();
    private final AtomicLong authorityVersion = new AtomicLong(1L);
    private final AtomicLong menuVersion = new AtomicLong(1L);

    /** 프로그램 마스터와 메뉴·프로그램 안에서 실행 가능한 기능 정의다. */
    private final ConcurrentMap<String, ProgramDefinition> programs = new ConcurrentHashMap<>();
    private final ConcurrentMap<ProgramActionKey, ProgramActionDefinition> programActions =
            new ConcurrentHashMap<>();

    /** 권한별 기능 허용 목록이다. 비활성 프로그램이나 기능은 매핑이 남아 있어도 효력이 없다. */
    private final ConcurrentMap<String, Set<ProgramActionKey>> authorityActionGrants =
            new ConcurrentHashMap<>();
    private final AtomicLong programVersion = new AtomicLong(1L);

    public AuthorizationCatalog() {
        List.of(
                new AuthorityDefinition(AUTH_SYSTEM_ADMIN, "시스템 관리자", true),
                new AuthorityDefinition(AUTH_CONTENT_MANAGER, "콘텐츠 관리자", true),
                new AuthorityDefinition(AUTH_VIEWER, "조회 사용자", true),
                new AuthorityDefinition("AUTH_DISABLED", "사용 중지 권한", false))
                .forEach(authority -> authorities.put(authority.id(), authority));

        List.of(
                new MenuDefinition("HOME", null, "홈", "#home", 10, true, true, true),
                new MenuDefinition("CONTENT", null, "콘텐츠", null, 20, true, true, false),
                new MenuDefinition("CONTENT_LIST", "CONTENT", "콘텐츠 조회", "#content", 10, true, true, false),
                new MenuDefinition("SYSTEM", null, "시스템 관리", null, 30, true, true, false),
                new MenuDefinition("SYSTEM_AUTH_MASTER", "SYSTEM", "권한 마스터", "#authority-master", 5, true, true, false),
                new MenuDefinition("SYSTEM_AUTH", "SYSTEM", "사용자 권한", "#authority", 10, true, true, false),
                new MenuDefinition("SYSTEM_MENU", "SYSTEM", "메뉴 관리", "#menu", 20, true, true, false),
                new MenuDefinition("SYSTEM_PROGRAM", "SYSTEM", "프로그램 관리", "#program", 30, true, true, false),
                new MenuDefinition("SYSTEM_CODE", "SYSTEM", "공통코드 관리", "#common-code", 40, true, true, false),
                new MenuDefinition("HIDDEN_MENU", "SYSTEM", "숨김 메뉴", "#hidden", 99, true, false, false))
                .forEach(menu -> menus.put(menu.id(), menu));
        registerMenuGrants(AUTH_SYSTEM_ADMIN,
                Set.of("CONTENT_LIST", "SYSTEM_AUTH_MASTER", "SYSTEM_AUTH", "SYSTEM_MENU",
                        "SYSTEM_PROGRAM", "SYSTEM_CODE", "HIDDEN_MENU"));
        registerMenuGrants(AUTH_CONTENT_MANAGER, Set.of("CONTENT_LIST"));
        registerMenuGrants(AUTH_VIEWER, Set.of("CONTENT_LIST"));

        List.of(
                new ProgramDefinition("CONTENT", "콘텐츠 관리", "콘텐츠 조회·저장·게시 업무", true),
                new ProgramDefinition("AUTHORITY", "권한 관리", "권한·메뉴·프로그램 관리 업무", true),
                new ProgramDefinition("COMMON_CODE", "공통코드 관리", "공통코드 조회·저장 업무", true))
                .forEach(program -> programs.put(program.id(), program));

        List.of(
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_READ", "조회", "btnContentRead", 10, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_SAVE", "저장", "btnContentSave", 20, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH", "게시", "btnContentPublish", 30, true),
                new ProgramActionDefinition("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_READ", "관리 현황 조회", "btnAuthorityRead", 10, true),
                new ProgramActionDefinition("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_UPDATE", "관리 정보 변경", "btnAuthorityUpdate", 20, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_SAVE", "공통코드 저장", "btnCommonCodeSave", 10, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_DELETE_OLD", "폐기 기능", "btnOldDelete", 99, false))
                .forEach(this::registerAction);

        registerActionGrants(AUTH_SYSTEM_ADMIN, Set.of(
                grant("CONTENT_LIST", "CONTENT", "CONTENT_READ"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_SAVE"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH"),
                grant("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_READ"),
                grant("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_UPDATE"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_SAVE"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_DELETE_OLD")));
        registerActionGrants(AUTH_CONTENT_MANAGER, Set.of(
                grant("CONTENT_LIST", "CONTENT", "CONTENT_READ"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_SAVE"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH")));
        registerActionGrants(AUTH_VIEWER,
                Set.of(grant("CONTENT_LIST", "CONTENT", "CONTENT_READ")));
    }

    /** 권한 ID로 마스터 정의를 조회한다. 존재하지 않는 권한은 Optional.empty()로 반환한다. */
    public Optional<AuthorityDefinition> findAuthority(String authorityId) {
        return Optional.ofNullable(authorities.get(authorityId));
    }

    /** 관리 화면에 표시할 전체 권한 마스터를 ID 순서로 반환한다. */
    public List<AuthorityDefinition> authorities() {
        return authorities.values().stream()
                .sorted(Comparator.comparing(AuthorityDefinition::id))
                .toList();
    }

    /** 인증 사용자명으로 현재 조직 정보가 포함된 사용자 프로필을 조회한다. */
    public Optional<UserProfile> findUser(String username) {
        return Optional.ofNullable(users.get(username));
    }

    /** 권한 관리 대상 전체 사용자 프로필을 사용자명 순서로 반환한다. */
    public List<UserProfile> users() {
        return users.values().stream()
                .sorted(Comparator.comparing(UserProfile::username))
                .toList();
    }

    /** 검증된 사용자 관리 서비스가 현재 조직·사용 여부를 변경한다. 이전 조직 권한 이력은 보존한다. */
    public synchronized UserProfile saveUserProfile(String username, String organizationId, boolean active) {
        if (username == null || !username.matches("[A-Za-z0-9_]{1,50}")
                || organizationId == null || organizationId.isBlank() || organizationId.length() > 101) {
            throw new IllegalArgumentException("사용자 ID 또는 조직 코드가 올바르지 않습니다.");
        }
        if ("admin".equals(username) && (!active || !"HQ".equals(organizationId))) {
            throw new ApiException(ErrorCode.CONFLICT, "기본 관리자의 접근을 유지해야 합니다.");
        }
        UserProfile saved = new UserProfile(username, organizationId, active);
        users.put(username, saved); authorityVersion.incrementAndGet();
        return saved;
    }

    /** 한 사용자의 모든 직접·위임 권한 이력을 반환한다. 최신 이력 선택은 서비스가 담당한다. */
    public synchronized List<AuthorityAssignment> assignmentsFor(String username) {
        return assignments.stream()
                .filter(assignment -> assignment.username().equals(username))
                .toList();
    }

    /** 전체 메뉴 마스터를 반환한다. 노출·접근 필터링은 메뉴 권한 서비스가 담당한다. */
    public List<MenuDefinition> menus() {
        return menus.values().stream()
                .sorted(Comparator.comparingInt(MenuDefinition::sortOrder)
                        .thenComparing(MenuDefinition::id))
                .toList();
    }

    /** 지정한 권한이 직접 허용하는 메뉴 ID 집합을 반환한다. */
    public Set<String> menuGrantsFor(String authorityId) {
        return Set.copyOf(authorityMenuGrants.getOrDefault(authorityId, Set.of()));
    }

    /** 관리 화면에 표시할 전체 권한-메뉴 매핑을 외부에서 수정할 수 없는 복사본으로 반환한다. */
    public Map<String, Set<String>> allMenuGrants() {
        return authorityMenuGrants.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    /** 등록된 전체 프로그램 기능 정의를 반환한다. */
    public List<ProgramActionDefinition> programActions() {
        return programActions.values().stream()
                .sorted(Comparator.comparing(ProgramActionDefinition::menuId)
                        .thenComparing(ProgramActionDefinition::programId)
                        .thenComparingInt(ProgramActionDefinition::sortOrder)
                        .thenComparing(ProgramActionDefinition::actionId))
                .toList();
    }

    /** 지정한 권한이 허용하는 메뉴·프로그램·기능 복합 키 집합을 반환한다. */
    public Set<ProgramActionKey> actionGrantsFor(String authorityId) {
        return Set.copyOf(authorityActionGrants.getOrDefault(authorityId, Set.of()));
    }

    /** 관리 화면에 표시할 전체 프로그램 마스터를 ID 순서로 반환한다. */
    public List<ProgramDefinition> programs() {
        return programs.values().stream()
                .sorted(Comparator.comparing(ProgramDefinition::id))
                .toList();
    }

    /** 지정한 프로그램이 존재하고 활성 상태인지 확인한다. */
    public boolean isProgramActive(String programId) {
        ProgramDefinition program = programs.get(programId);
        return program != null && program.active();
    }

    /** 관리 화면에 표시할 전체 권한-기능 매핑을 수정 불가능한 복사본으로 반환한다. */
    public Map<String, Set<ProgramActionKey>> allActionGrants() {
        return authorityActionGrants.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    /**
     * 사용자 권한 이력이 바뀔 때 증가하는 현재 권한 버전을 반환한다.
     */
    public long authorityVersion() {
        return authorityVersion.get();
    }

    /**
     * 메뉴 마스터 또는 권한-메뉴 매핑이 바뀔 때 증가하는 현재 메뉴 버전을 반환한다.
     */
    public long menuVersion() {
        return menuVersion.get();
    }

    /** 프로그램 마스터, 기능 또는 권한-기능 매핑이 바뀔 때 증가하는 버전이다. */
    public long programVersion() {
        return programVersion.get();
    }

    /** 권한 마스터 한 건을 신규 등록하거나 표시명·활성 상태를 수정한다. */
    public synchronized AuthorityDefinition saveAuthority(AuthorityDefinition request) {
        String authorityId = normalizeId(request.id(), "authorityId");
        String name = normalizeName(request.name());
        if (AUTH_SYSTEM_ADMIN.equals(authorityId) && !request.active()) {
            throw new ApiException(ErrorCode.CONFLICT, authorityId);
        }
        String systemId = normalizeId(request.systemId(), "systemId");
        String classificationId = normalizeOptionalId(request.classificationId(), "classificationId");
        if (classificationId != null) {
            AuthorityClassification classification = classifications.get(classificationId);
            if (classification == null || !classification.active() || !classification.systemId().equals(systemId)) {
                throw new ApiException(ErrorCode.CONFLICT, classificationId);
            }
        }
        AuthorityDefinition existing = authorities.get(authorityId);
        if (existing != null && !existing.systemId().equals(systemId)
                && assignments.stream().anyMatch(item -> item.authorityId().equals(authorityId))) {
            throw new ApiException(ErrorCode.CONFLICT, authorityId);
        }
        AuthorityDefinition saved = new AuthorityDefinition(authorityId, name, request.active(),
                systemId, classificationId, normalizeDescription(request.description()));
        AuthorityDefinition previous = authorities.put(authorityId, saved);
        if (!saved.equals(previous)) {
            authorityVersion.incrementAndGet();
        }
        return saved;
    }

    /** 분류 목록을 시스템·ID 순으로 반환해 화면에서 동일한 계층을 구성한다. */
    public List<AuthorityClassification> classifications() {
        return classifications.values().stream()
                .sorted(Comparator.comparing(AuthorityClassification::systemId)
                        .thenComparing(AuthorityClassification::id)).toList();
    }

    /** 시스템 간 연결, 자기/간접 순환 및 사용 중인 분류의 비활성화를 차단한다. */
    public synchronized AuthorityClassification saveClassification(AuthorityClassification request) {
        String id = normalizeId(request.id(), "classificationId");
        String parentId = normalizeOptionalId(request.parentId(), "parentId");
        String systemId = normalizeId(request.systemId(), "systemId");
        String name = normalizeName(request.name());
        Set<String> visited = new HashSet<>();
        visited.add(id);
        String next = parentId;
        while (next != null) {
            if (!visited.add(next)) {
                throw new IllegalArgumentException("권한 분류에 순환 관계를 만들 수 없습니다.");
            }
            AuthorityClassification parent = classifications.get(next);
            if (parent == null || !parent.active() || !parent.systemId().equals(systemId)) {
                throw new ApiException(ErrorCode.CONFLICT, next);
            }
            next = parent.parentId();
        }
        boolean inUse = classifications.values().stream()
                .anyMatch(item -> id.equals(item.parentId()) && item.active())
                || authorities.values().stream()
                .anyMatch(item -> id.equals(item.classificationId()) && item.active());
        AuthorityClassification previous = classifications.get(id);
        boolean referenced = classifications.values().stream().anyMatch(item -> id.equals(item.parentId()))
                || authorities.values().stream().anyMatch(item -> id.equals(item.classificationId()));
        if ((!request.active() && inUse) || (previous != null && referenced
                && !previous.systemId().equals(systemId))) {
            throw new ApiException(ErrorCode.CONFLICT, id);
        }
        AuthorityClassification saved = new AuthorityClassification(id, parentId, name, systemId, request.active());
        classifications.put(id, saved);
        authorityVersion.incrementAndGet();
        return saved;
    }

    /** 사용 중인 연결을 남기지 않도록 자식과 연결 권한이 없는 분류만 삭제한다. */
    public synchronized void deleteClassification(String classificationId) {
        String id = normalizeId(classificationId, "classificationId");
        if (!classifications.containsKey(id)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id);
        }
        if (classifications.values().stream().anyMatch(item -> id.equals(item.parentId()))
                || authorities.values().stream().anyMatch(item -> id.equals(item.classificationId()))) {
            throw new ApiException(ErrorCode.CONFLICT, id);
        }
        classifications.remove(id);
        authorityVersion.incrementAndGet();
    }

    /** 프로그램 마스터 한 건을 신규 등록하거나 표시명·설명·활성 상태를 수정한다. */
    public synchronized ProgramDefinition saveProgram(ProgramDefinition request) {
        String programId = normalizeId(request.id(), "programId");
        String name = normalizeName(request.name());
        String description = normalizeDescription(request.description());
        if ("AUTHORITY".equals(programId) && !request.active()) {
            throw new ApiException(ErrorCode.CONFLICT, programId);
        }
        ProgramDefinition saved = new ProgramDefinition(
                programId, name, description, request.active());
        ProgramDefinition previous = programs.put(programId, saved);
        if (!saved.equals(previous)) {
            programVersion.incrementAndGet();
        }
        return saved;
    }

    /** 메뉴·프로그램 문맥에 속한 기능 한 건을 신규 등록하거나 수정한다. */
    public synchronized ProgramActionDefinition saveAction(ProgramActionDefinition request) {
        String menuId = normalizeId(request.menuId(), "menuId");
        String programId = normalizeId(request.programId(), "programId");
        String actionId = normalizeId(request.actionId(), "actionId");
        if (!menus.containsKey(menuId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, menuId);
        }
        if (!programs.containsKey(programId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, programId);
        }
        ProgramActionKey key = new ProgramActionKey(menuId, programId, actionId);
        if (isCoreAction(key) && !request.active()) {
            throw new ApiException(ErrorCode.CONFLICT, actionId);
        }
        ProgramActionDefinition saved = new ProgramActionDefinition(
                menuId,
                programId,
                actionId,
                normalizeName(request.label()),
                normalizeComponent(request.componentId()),
                normalizeSort(request.sortOrder()),
                request.active());
        ProgramActionDefinition previous = programActions.put(key, saved);
        if (!saved.equals(previous)) {
            programVersion.incrementAndGet();
        }
        return saved;
    }

    /** 권한과 프로그램 기능 사이의 직접 허용 매핑을 추가하거나 제거한다. */
    public synchronized Set<ProgramActionKey> setActionGrant(
            String authorityId,
            ProgramActionKey actionKey,
            boolean granted) {
        String normalizedAuthorityId = normalizeId(authorityId, "authorityId");
        ProgramActionKey normalizedKey = new ProgramActionKey(
                normalizeId(actionKey.menuId(), "menuId"),
                normalizeId(actionKey.programId(), "programId"),
                normalizeId(actionKey.actionId(), "actionId"));
        if (!authorities.containsKey(normalizedAuthorityId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedAuthorityId);
        }
        if (!programActions.containsKey(normalizedKey)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedKey.actionId());
        }
        if (AUTH_SYSTEM_ADMIN.equals(normalizedAuthorityId)
                && isCoreAction(normalizedKey) && !granted) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedKey.actionId());
        }
        Set<ProgramActionKey> grants = authorityActionGrants.computeIfAbsent(
                normalizedAuthorityId, ignored -> ConcurrentHashMap.newKeySet());
        boolean changed = granted ? grants.add(normalizedKey) : grants.remove(normalizedKey);
        if (changed) {
            programVersion.incrementAndGet();
        }
        return Set.copyOf(grants);
    }

    /** 승인된 직접 또는 위임 권한 이력을 추가하고 권한 버전을 증가시킨다. */
    public synchronized AuthorityAssignment saveAssignment(
            String username,
            String authorityId,
            AssignmentType type,
            LocalDate validFrom,
            LocalDate validTo,
            String delegatedBy) {
        UserProfile user = findUser(username)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
        String normalizedAuthorityId = normalizeId(authorityId, "authorityId");
        if (!authorities.containsKey(normalizedAuthorityId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedAuthorityId);
        }
        validatePeriod(validFrom, validTo);
        long nextSequence = findNextSequence(username, user.organizationId(), normalizedAuthorityId, type);
        AuthorityAssignment saved = new AuthorityAssignment(
                username,
                user.organizationId(),
                normalizedAuthorityId,
                type,
                AssignmentStatus.APPROVED,
                validFrom,
                validTo,
                type == AssignmentType.DELEGATED ? delegatedBy : null,
                nextSequence);
        assignments.add(saved);
        authorityVersion.incrementAndGet();
        return saved;
    }

    /** 같은 사용자·권한·부여 유형의 최신 이력을 REVOKED 상태로 추가한다. */
    public synchronized AuthorityAssignment revokeAssignment(
            String username,
            String authorityId,
            AssignmentType type) {
        UserProfile user = findUser(username)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
        String normalizedAuthorityId = normalizeId(authorityId, "authorityId");
        if ("admin".equals(username) && AUTH_SYSTEM_ADMIN.equals(normalizedAuthorityId) && type == AssignmentType.DIRECT) {
            throw new ApiException(ErrorCode.CONFLICT, "기본 관리자의 필수 권한은 회수할 수 없습니다.");
        }
        AuthorityAssignment latest = assignments.stream()
                .filter(item -> item.username().equals(username))
                .filter(item -> item.organizationId().equals(user.organizationId()))
                .filter(item -> item.authorityId().equals(normalizedAuthorityId))
                .filter(item -> item.type() == type)
                .max(Comparator.comparingLong(AuthorityAssignment::sequence))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedAuthorityId));
        if (latest.status() == AssignmentStatus.REVOKED) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedAuthorityId);
        }
        AuthorityAssignment revoked = new AuthorityAssignment(
                latest.username(),
                latest.organizationId(),
                latest.authorityId(),
                latest.type(),
                AssignmentStatus.REVOKED,
                latest.validFrom(),
                latest.validTo(),
                latest.delegatedBy(),
                latest.sequence() + 1);
        assignments.add(revoked);
        authorityVersion.incrementAndGet();
        return revoked;
    }

    /** 메뉴 한 건을 신규 등록하거나 수정하고 상위 메뉴 순환 참조를 차단한다. */
    public synchronized MenuDefinition saveMenu(MenuDefinition request) {
        String menuId = normalizeId(request.id(), "menuId");
        String parentId = normalizeOptionalId(request.parentId(), "parentId");
        String name = normalizeName(request.name());
        String path = normalizePath(request.path());
        if (parentId != null && !menus.containsKey(parentId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, parentId);
        }
        if ("SYSTEM_AUTH".equals(menuId) && !request.active()) {
            throw new ApiException(ErrorCode.CONFLICT, menuId);
        }
        validateMenuParent(menuId, parentId);
        MenuDefinition saved = new MenuDefinition(
                menuId,
                parentId,
                name,
                path,
                request.sortOrder(),
                request.active(),
                request.displayed(),
                request.publicMenu());
        menus.put(menuId, saved);
        menuVersion.incrementAndGet();
        return saved;
    }

    /** 권한과 메뉴 사이의 직접 허용 매핑을 추가하거나 제거한다. */
    public synchronized Set<String> setMenuGrant(
            String authorityId,
            String menuId,
            boolean granted) {
        String normalizedAuthorityId = normalizeId(authorityId, "authorityId");
        String normalizedMenuId = normalizeId(menuId, "menuId");
        if (!authorities.containsKey(normalizedAuthorityId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedAuthorityId);
        }
        if (!menus.containsKey(normalizedMenuId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedMenuId);
        }
        if (AUTH_SYSTEM_ADMIN.equals(normalizedAuthorityId)
                && "SYSTEM_AUTH".equals(normalizedMenuId) && !granted) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedMenuId);
        }
        Set<String> grants = authorityMenuGrants.computeIfAbsent(
                normalizedAuthorityId, ignored -> ConcurrentHashMap.newKeySet());
        boolean changed = granted ? grants.add(normalizedMenuId) : grants.remove(normalizedMenuId);
        if (!granted) {
            Set<ProgramActionKey> actions = authorityActionGrants.get(normalizedAuthorityId);
            if (actions != null && actions.removeIf(key -> key.menuId().equals(normalizedMenuId))) {
                programVersion.incrementAndGet();
            }
        }
        if (changed) {
            menuVersion.incrementAndGet();
        }
        return Set.copyOf(grants);
    }

    /** 초기 권한-메뉴 매핑을 수정 가능한 동시성 집합에 등록한다. */
    private void registerMenuGrants(String authorityId, Set<String> menuIds) {
        Set<String> grants = ConcurrentHashMap.newKeySet();
        grants.addAll(menuIds);
        authorityMenuGrants.put(authorityId, grants);
    }

    /** 초기 권한-기능 매핑을 수정 가능한 동시성 집합에 등록한다. */
    private void registerActionGrants(String authorityId, Set<ProgramActionKey> actionKeys) {
        Set<ProgramActionKey> grants = ConcurrentHashMap.newKeySet();
        grants.addAll(actionKeys);
        authorityActionGrants.put(authorityId, grants);
    }

    /** 초기 기능 키가 중복되면 서로 다른 정의가 덮어쓰지 못하도록 기동을 중단한다. */
    private void registerAction(ProgramActionDefinition action) {
        if (programActions.putIfAbsent(action.key(), action) != null) {
            throw new IllegalStateException("중복 프로그램 기능 키입니다: " + action.key());
        }
    }

    /** 새 권한 이력이 사용할 다음 sequence를 계산한다. */
    private long findNextSequence(
            String username,
            String organizationId,
            String authorityId,
            AssignmentType type) {
        return assignments.stream()
                .filter(item -> item.username().equals(username))
                .filter(item -> item.organizationId().equals(organizationId))
                .filter(item -> item.authorityId().equals(authorityId))
                .filter(item -> item.type() == type)
                .mapToLong(AuthorityAssignment::sequence)
                .max()
                .orElse(0L) + 1L;
    }

    /** 메뉴 상위 체인을 따라가며 자기 참조와 간접 순환 참조를 검사한다. */
    private void validateMenuParent(String menuId, String parentId) {
        String ancestorId = parentId;
        Set<String> visited = new HashSet<>();
        while (ancestorId != null) {
            if (ancestorId.equals(menuId) || !visited.add(ancestorId)) {
                throw new IllegalArgumentException("메뉴 순환 참조가 발생할 수 없습니다.");
            }
            MenuDefinition ancestor = menus.get(ancestorId);
            ancestorId = ancestor == null ? null : ancestor.parentId();
        }
    }

    /** 필수 식별자를 대문자로 정규화하고 허용 문자와 길이를 검사한다. */
    private String normalizeId(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "는 필수입니다.");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    /** 선택 식별자는 빈 값을 null로 보존하고 값이 있으면 필수 식별자 규칙을 적용한다. */
    private String normalizeOptionalId(String value, String fieldName) {
        return value == null || value.isBlank() ? null : normalizeId(value, fieldName);
    }

    /** 메뉴 표시명의 필수 여부와 최대 길이를 검사한다. */
    private String normalizeName(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 100) {
            throw new IllegalArgumentException("name은 1~100자의 값이어야 합니다.");
        }
        return value.trim();
    }

    /** 프로그램 설명은 선택 값으로 처리하되 관리 표의 최대 길이를 제한한다. */
    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("description은 200자 이하여야 합니다.");
        }
        return normalized;
    }

    /** 화면 컴포넌트 ID는 선택 값이며 DOM ID로 안전한 문자만 허용한다. */
    private String normalizeComponent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_-]{0,99}")) {
            throw new IllegalArgumentException("componentId 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    /** 관리 화면과 서비스 직접 호출 모두에서 기능 정렬 순위를 같은 범위로 제한한다. */
    private int normalizeSort(int sortOrder) {
        if (sortOrder < 0 || sortOrder > 9999) {
            throw new IllegalArgumentException("sortOrder는 0~9999 사이여야 합니다.");
        }
        return sortOrder;
    }

    /** 관리 기능을 스스로 제거해 전체 관리 화면이 잠기는 변경인지 확인한다. */
    private boolean isCoreAction(ProgramActionKey key) {
        return "SYSTEM_AUTH".equals(key.menuId())
                && "AUTHORITY".equals(key.programId())
                && Set.of("AUTHORITY_READ", "AUTHORITY_UPDATE").contains(key.actionId());
    }

    /** 화면 내부 앵커만 메뉴 경로로 허용해 임의 외부 URL 이동을 막는다. */
    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 200 || !normalized.matches("#[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("path는 #으로 시작하는 화면 앵커여야 합니다.");
        }
        return normalized;
    }

    /** 권한 적용 시작일과 종료일의 필수 여부 및 순서를 검사한다. */
    private void validatePeriod(LocalDate validFrom, LocalDate validTo) {
        if (validFrom == null || validTo == null || validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("권한 적용 기간을 확인해 주세요.");
        }
    }

    /** 승인된 직접 권한 이력을 만드는 데모 데이터 생성 보조 메서드다. */
    private static AuthorityAssignment direct(String username, String authorityId) {
        return new AuthorityAssignment(username, "HQ", authorityId, AssignmentType.DIRECT,
                AssignmentStatus.APPROVED, LONG_AGO, FAR_FUTURE, null, 1);
    }

    /** 승인된 위임 권한 이력을 만드는 데모 데이터 생성 보조 메서드다. */
    private static AuthorityAssignment delegated(
            String username,
            String authorityId,
            String delegatedBy,
            LocalDate validFrom,
            LocalDate validTo) {
        return new AuthorityAssignment(username, "HQ", authorityId, AssignmentType.DELEGATED,
                AssignmentStatus.APPROVED, validFrom, validTo, delegatedBy, 1);
    }

    /** 문자열 세 개를 기능 권한의 복합 키로 묶는다. */
    private static ProgramActionKey grant(String menuId, String programId, String actionId) {
        return new ProgramActionKey(menuId, programId, actionId);
    }

    /** 권한이 사용자에게 직접 부여됐는지 다른 사용자가 위임했는지를 나타낸다. */
    public enum AssignmentType {
        DIRECT,
        DELEGATED
    }

    /** 권한 이력의 승인 상태다. APPROVED만 유효 권한 후보가 된다. */
    public enum AssignmentStatus {
        PENDING,
        APPROVED,
        REVOKED
    }

    /** 권한 마스터의 식별자, 표시명과 활성 상태다. */
    public record AuthorityDefinition(String id, String name, boolean active,
            String systemId, String classificationId, String description) {
        /** 기존 가상 권한은 INFO 시스템으로 분류하며 원본 운영 값을 가져오지 않는다. */
        public AuthorityDefinition(String id, String name, boolean active) {
            this(id, name, active, "INFO", null, "");
        }
    }

    /** 원본 권한분류의 상위 관계, 시스템 범위와 사용 여부다. */
    public record AuthorityClassification(String id, String parentId, String name, String systemId, boolean active) {
    }

    /** 프로그램 마스터의 식별자, 표시명, 설명과 활성 상태다. */
    public record ProgramDefinition(
            String id,
            String name,
            String description,
            boolean active) {
    }

    /** 사용자의 현재 조직을 포함한 최소 프로필이다. */
    public record UserProfile(String username, String organizationId, Boolean active) {
        public UserProfile { if (active == null) active = true; }
        public UserProfile(String username, String organizationId) { this(username, organizationId, true); }
    }

    /**
     * 사용자 권한 이력 한 건이다.
     * sequence는 같은 권한 이력 중 최신 상태를 결정하고, delegatedBy는 위임 원천 사용자를 가리킨다.
     */
    public record AuthorityAssignment(
            String username,
            String organizationId,
            String authorityId,
            AssignmentType type,
            AssignmentStatus status,
            LocalDate validFrom,
            LocalDate validTo,
            String delegatedBy,
            long sequence) {

        /** 승인 상태이고 기준일이 시작일과 종료일 사이에 포함되는지 확인한다. */
        public boolean isEffectiveOn(LocalDate date) {
            return status == AssignmentStatus.APPROVED
                    && !date.isBefore(validFrom)
                    && !date.isAfter(validTo);
        }
    }

    /** 메뉴 계층 및 화면 노출·접근 판단에 필요한 메뉴 마스터 정보다. */
    public record MenuDefinition(
            String id,
            String parentId,
            String name,
            String path,
            int sortOrder,
            boolean active,
            boolean displayed,
            boolean publicMenu) {
    }

    /** 메뉴·프로그램 안에서 실행 가능한 한 개 기능과 연결된 화면 컴포넌트 정보다. */
    public record ProgramActionDefinition(
            String menuId,
            String programId,
            String actionId,
            String label,
            String componentId,
            int sortOrder,
            boolean active) {

        /** 현재 정의를 권한 매핑 비교에 사용하는 복합 키로 변환한다. */
        public ProgramActionKey key() {
            return new ProgramActionKey(menuId, programId, actionId);
        }
    }

    /** 메뉴 ID만으로 다른 프로그램의 같은 기능명이 섞이지 않도록 사용하는 복합 키다. */
    public record ProgramActionKey(String menuId, String programId, String actionId) {
    }

    @Override public String stateKey() { return "authorization"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() {
        return new StoredState(authorities(), classifications(), users(), List.copyOf(assignments), menus(),
                allMenuGrants(), programs(), programActions(), allActionGrants(),
                authorityVersion.get(), menuVersion.get(), programVersion.get());
    }
    @Override public void restoreState(Object raw) {
        StoredState state = (StoredState) raw;
        authorities.clear(); state.authorities().forEach(item -> authorities.put(item.id(), item));
        classifications.clear(); state.classifications().forEach(item -> classifications.put(item.id(), item));
        users.clear(); state.users().forEach(item -> users.put(item.username(), item));
        assignments.clear(); assignments.addAll(state.assignments());
        menus.clear(); state.menus().forEach(item -> menus.put(item.id(), item));
        authorityMenuGrants.clear(); state.menuGrants().forEach(this::registerMenuGrants);
        programs.clear(); state.programs().forEach(item -> programs.put(item.id(), item));
        programActions.clear(); state.actions().forEach(item -> programActions.put(item.key(), item));
        authorityActionGrants.clear(); state.actionGrants().forEach(this::registerActionGrants);
        authorityVersion.set(state.authorityVersion()); menuVersion.set(state.menuVersion()); programVersion.set(state.programVersion());
    }
    public record StoredState(List<AuthorityDefinition> authorities, List<AuthorityClassification> classifications,
            List<UserProfile> users, List<AuthorityAssignment> assignments, List<MenuDefinition> menus,
            Map<String, Set<String>> menuGrants, List<ProgramDefinition> programs, List<ProgramActionDefinition> actions,
            Map<String, Set<ProgramActionKey>> actionGrants, long authorityVersion, long menuVersion, long programVersion) { }
}
