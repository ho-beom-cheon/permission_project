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

import com.example.permissiondemo.authorization.persistence.AuthorizationCatalogPersistence;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 권한 마스터, 사용자 권한 이력, 메뉴와 프로그램 기능 매핑을 한곳에 보관하는 데모 카탈로그다.
 *
 * <p>demo 프로필에서는 실행 중 변경 가능한 테스트 데이터를 사용하고, oracle 프로필에서는
 * PM_* 원장을 시작 시 로드한 뒤 같은 조회 계약을 유지하면서 모든 관리 변경을 DB에 먼저 반영한다.</p>
 */
@Component
public class AuthorizationCatalog {

    public static final String AUTH_SYSTEM_ADMIN = "AUTH_SYSTEM_ADMIN";
    public static final String AUTH_AUTHORITY_MANAGER = "AUTH_AUTHORITY_MANAGER";
    public static final String AUTH_PROGRAM_MANAGER = "AUTH_PROGRAM_MANAGER";
    public static final String AUTH_PROJECT_MANAGER = "AUTH_PROJECT_MANAGER";
    public static final String AUTH_CODE_MANAGER = "AUTH_CODE_MANAGER";
    public static final String AUTH_CONTENT_MANAGER = "AUTH_CONTENT_MANAGER";
    public static final String AUTH_AUDITOR = "AUTH_AUDITOR";
    public static final String AUTH_VIEWER = "AUTH_VIEWER";

    private static final LocalDate LONG_AGO = LocalDate.of(2020, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Z0-9_]{1,50}");

    /** 사용 가능 여부를 포함한 권한 마스터다. 관리 화면 저장을 반영할 수 있도록 동시성 맵에 보관한다. */
    private final ConcurrentMap<String, AuthorityDefinition> authorities = new ConcurrentHashMap<>();

    /** 조직 선택·사용자 검색에 사용하는 계층형 조직 마스터다. */
    private final ConcurrentMap<String, OrganizationDefinition> organizations =
            new ConcurrentHashMap<>(Map.ofEntries(
            Map.entry("HQ", new OrganizationDefinition("HQ", "본사", null, true)),
            Map.entry("PLATFORM", new OrganizationDefinition("PLATFORM", "플랫폼실", "HQ", true)),
            Map.entry("CONTENT", new OrganizationDefinition("CONTENT", "콘텐츠실", "HQ", true)),
            Map.entry("AUDIT", new OrganizationDefinition("AUDIT", "감사실", "HQ", true)),
            Map.entry("BRANCH_A", new OrganizationDefinition("BRANCH_A", "이전 지점", "HQ", false)),
            Map.entry("BRANCH_B", new OrganizationDefinition("BRANCH_B", "중부 지점", "HQ", true)),
            Map.entry("BRANCH_SEOUL", new OrganizationDefinition("BRANCH_SEOUL", "서울 지점", "HQ", true)),
            Map.entry("BRANCH_BUSAN", new OrganizationDefinition("BRANCH_BUSAN", "부산 지점", "HQ", true))));

    /** 사용자의 현재 소속 조직이다. 권한 이력의 조직과 일치하는 경우에만 현재 권한으로 평가한다. */
    private final ConcurrentMap<String, UserProfile> users = new ConcurrentHashMap<>(Map.ofEntries(
            Map.entry("admin", new UserProfile("admin", "HQ")),
            Map.entry("backupAdmin", new UserProfile("backupAdmin", "HQ")),
            Map.entry("authorityManager", new UserProfile("authorityManager", "HQ")),
            Map.entry("programManager", new UserProfile("programManager", "PLATFORM")),
            Map.entry("projectManager", new UserProfile("projectManager", "HQ")),
            Map.entry("codeManager", new UserProfile("codeManager", "PLATFORM")),
            Map.entry("auditor", new UserProfile("auditor", "AUDIT")),
            Map.entry("contentEditor", new UserProfile("contentEditor", "CONTENT")),
            Map.entry("seoulOperator", new UserProfile("seoulOperator", "BRANCH_SEOUL")),
            Map.entry("busanViewer", new UserProfile("busanViewer", "BRANCH_BUSAN")),
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
            direct("backupAdmin", AUTH_SYSTEM_ADMIN),
            direct("authorityManager", "HQ", AUTH_AUTHORITY_MANAGER),
            direct("programManager", "PLATFORM", AUTH_PROGRAM_MANAGER),
            direct("projectManager", "HQ", AUTH_PROJECT_MANAGER),
            direct("codeManager", "PLATFORM", AUTH_CODE_MANAGER),
            direct("auditor", "AUDIT", AUTH_AUDITOR),
            direct("contentEditor", "CONTENT", AUTH_CONTENT_MANAGER),
            direct("seoulOperator", "BRANCH_SEOUL", AUTH_PROJECT_MANAGER),
            direct("busanViewer", "BRANCH_BUSAN", AUTH_VIEWER),
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
    private AuthorizationCatalogPersistence persistence;

    public AuthorizationCatalog() {
        List.of(
                new AuthorityDefinition(AUTH_SYSTEM_ADMIN, "시스템 관리자", true),
                new AuthorityDefinition(AUTH_AUTHORITY_MANAGER, "권한 관리자", true),
                new AuthorityDefinition(AUTH_PROGRAM_MANAGER, "프로그램 관리자", true),
                new AuthorityDefinition(AUTH_PROJECT_MANAGER, "프로젝트 관리자", true),
                new AuthorityDefinition(AUTH_CODE_MANAGER, "공통코드 관리자", true),
                new AuthorityDefinition(AUTH_CONTENT_MANAGER, "콘텐츠 관리자", true),
                new AuthorityDefinition(AUTH_AUDITOR, "감사 조회자", true),
                new AuthorityDefinition(AUTH_VIEWER, "조회 사용자", true),
                new AuthorityDefinition("AUTH_DISABLED", "사용 중지 권한", false))
                .forEach(authority -> authorities.put(authority.id(), authority));

        List.of(
                new MenuDefinition("HOME", null, "홈", "#home", 10, true, true, true, MenuArea.USER),
                new MenuDefinition("DASHBOARD", null, "운영 대시보드", "#home", 15, true, false, true, MenuArea.USER),
                new MenuDefinition("MY_AUTH_REQUEST", null, "권한 신청", "#authority-request", 18, true, true, true, MenuArea.USER),
                new MenuDefinition("CONTENT", null, "콘텐츠", null, 20, true, true, false, MenuArea.USER),
                new MenuDefinition("CONTENT_LIST", "CONTENT", "콘텐츠 조회", "#content", 10, true, true, false, MenuArea.USER),
                new MenuDefinition("CONTENT_EDITOR", "CONTENT", "콘텐츠 편집", "#content", 20, true, false, false, MenuArea.USER),
                new MenuDefinition("CONTENT_PUBLISH", "CONTENT", "게시 운영", "#content", 30, true, false, false, MenuArea.USER),
                new MenuDefinition("PROJECT", null, "프로젝트", null, 25, true, false, false, MenuArea.USER),
                new MenuDefinition("PROJECT_WORKSPACE", "PROJECT", "프로젝트 작업공간", "#project", 10, true, false, false, MenuArea.USER),
                new MenuDefinition("PROJECT_REPORT", "PROJECT", "프로젝트 현황", "#project", 20, true, false, false, MenuArea.USER),
                new MenuDefinition("SYSTEM", null, "시스템 관리", null, 30, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_AUTH_MASTER", "SYSTEM", "권한 마스터", "#authority-master", 5, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_AUTH", "SYSTEM", "사용자 권한", "#authority", 10, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_AUTH_REQUEST", "SYSTEM", "권한 요청 승인", "#authority-requests", 15, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_MENU", "SYSTEM", "메뉴 관리", "#menu", 20, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_PROGRAM", "SYSTEM", "프로그램 관리", "#program", 30, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_PROJECT", "SYSTEM", "프로젝트 관리", "#project", 35, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_CODE", "SYSTEM", "공통코드 관리", "#common-code", 40, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("SYSTEM_AUDIT", "SYSTEM", "감사 이력", "#audit", 50, true, true, false, MenuArea.ADMIN),
                new MenuDefinition("HIDDEN_MENU", "SYSTEM", "숨김 메뉴", "#hidden", 99, true, false, false, MenuArea.ADMIN))
                .forEach(menu -> menus.put(menu.id(), menu));
        registerMenuGrants(AUTH_SYSTEM_ADMIN,
                Set.of("CONTENT_LIST", "CONTENT_EDITOR", "CONTENT_PUBLISH", "PROJECT_WORKSPACE",
                        "PROJECT_REPORT", "SYSTEM_AUTH_MASTER", "SYSTEM_AUTH", "SYSTEM_AUTH_REQUEST",
                        "SYSTEM_MENU", "SYSTEM_PROGRAM", "SYSTEM_PROJECT", "SYSTEM_CODE",
                        "SYSTEM_AUDIT", "HIDDEN_MENU"));
        registerMenuGrants(AUTH_AUTHORITY_MANAGER,
                Set.of("SYSTEM_AUTH_MASTER", "SYSTEM_AUTH", "SYSTEM_AUTH_REQUEST", "SYSTEM_AUDIT"));
        registerMenuGrants(AUTH_PROGRAM_MANAGER, Set.of("SYSTEM_MENU", "SYSTEM_PROGRAM", "SYSTEM_AUDIT"));
        registerMenuGrants(AUTH_PROJECT_MANAGER,
                Set.of("PROJECT_WORKSPACE", "PROJECT_REPORT", "SYSTEM_PROJECT"));
        registerMenuGrants(AUTH_CODE_MANAGER, Set.of("SYSTEM_CODE", "SYSTEM_AUDIT"));
        registerMenuGrants(AUTH_CONTENT_MANAGER,
                Set.of("CONTENT_LIST", "CONTENT_EDITOR", "CONTENT_PUBLISH"));
        registerMenuGrants(AUTH_AUDITOR,
                Set.of("CONTENT_LIST", "PROJECT_REPORT", "SYSTEM_AUTH", "SYSTEM_PROGRAM",
                        "SYSTEM_PROJECT", "SYSTEM_CODE", "SYSTEM_AUDIT"));
        registerMenuGrants(AUTH_VIEWER, Set.of("CONTENT_LIST"));

        List.of(
                new ProgramDefinition("CONTENT", "콘텐츠 관리", "콘텐츠 조회·저장·게시 업무", true, ProgramScreen.CONTENT_DEMO),
                new ProgramDefinition("AUTHORITY", "권한 관리", "권한·메뉴·프로그램 관리 업무", true),
                new ProgramDefinition("MENU", "메뉴 관리", "계층 메뉴와 권한별 접근 매핑 관리", true),
                new ProgramDefinition("PROGRAM", "프로그램 관리", "프로그램 기능과 실행 권한 매핑 관리", true),
                new ProgramDefinition("PROJECT", "프로젝트 관리", "프로젝트와 참여자 관리 업무", true),
                new ProgramDefinition("COMMON_CODE", "공통코드 관리", "코드 그룹·상세 코드와 적용 기간 관리", true),
                new ProgramDefinition("AUDIT", "감사 이력", "관리 변경과 접근 이력 조회·내보내기", true),
                new ProgramDefinition("LEGACY_EXPORT", "레거시 내보내기", "사용 중지된 이전 출력 기능", false))
                .forEach(program -> programs.put(program.id(), program));

        List.of(
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_READ", "조회", "btnContentRead", 10, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_EXPORT", "목록 내보내기", "btnContentExport", 15, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_SAVE", "저장", "btnContentSave", 20, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH", "게시", "btnContentPublish", 30, true),
                new ProgramActionDefinition("CONTENT_EDITOR", "CONTENT", "CONTENT_CREATE", "신규 작성", "btnContentCreate", 10, true),
                new ProgramActionDefinition("CONTENT_EDITOR", "CONTENT", "CONTENT_REVIEW", "검수 요청", "btnContentReview", 20, true),
                new ProgramActionDefinition("CONTENT_PUBLISH", "CONTENT", "CONTENT_ARCHIVE", "게시물 보관", "btnContentArchive", 40, true),
                new ProgramActionDefinition("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_READ", "관리 현황 조회", "btnAuthorityRead", 10, true),
                new ProgramActionDefinition("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_UPDATE", "관리 정보 변경", "btnAuthorityUpdate", 20, true),
                new ProgramActionDefinition("SYSTEM_AUTH_REQUEST", "AUTHORITY", "AUTHORITY_REQUEST_READ", "권한 요청 조회", "btnAuthorityRequestRead", 10, true),
                new ProgramActionDefinition("SYSTEM_AUTH_REQUEST", "AUTHORITY", "AUTHORITY_REQUEST_APPROVE", "권한 요청 승인", "btnAuthorityApprove", 20, true),
                new ProgramActionDefinition("SYSTEM_AUTH_REQUEST", "AUTHORITY", "AUTHORITY_REQUEST_REJECT", "권한 요청 반려", "btnAuthorityReject", 30, true),
                new ProgramActionDefinition("SYSTEM_MENU", "MENU", "MENU_READ", "메뉴 조회", "btnMenuRead", 10, true),
                new ProgramActionDefinition("SYSTEM_MENU", "MENU", "MENU_SAVE", "메뉴 저장", "btnMenuSave", 20, true),
                new ProgramActionDefinition("SYSTEM_MENU", "MENU", "MENU_GRANT", "메뉴 권한 매핑", "btnMenuGrant", 30, true),
                new ProgramActionDefinition("SYSTEM_MENU", "MENU", "MENU_DEACTIVATE", "메뉴 비활성화", "btnMenuDeactivate", 40, true),
                new ProgramActionDefinition("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_READ", "프로그램 조회", "btnProgramRead", 10, true),
                new ProgramActionDefinition("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_SAVE", "프로그램 저장", "btnProgramSave", 20, true),
                new ProgramActionDefinition("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_ACTION_SAVE", "기능 저장", "btnProgramActionSave", 30, true),
                new ProgramActionDefinition("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_GRANT", "기능 권한 매핑", "btnProgramGrant", 40, true),
                new ProgramActionDefinition("SYSTEM_PROJECT", "PROJECT", "PROJECT_READ", "프로젝트 조회", "btnProjectRead", 10, true),
                new ProgramActionDefinition("SYSTEM_PROJECT", "PROJECT", "PROJECT_CREATE", "프로젝트 등록", "btnProjectCreate", 15, true),
                new ProgramActionDefinition("SYSTEM_PROJECT", "PROJECT", "PROJECT_UPDATE", "프로젝트 변경", "btnProjectSave", 20, true),
                new ProgramActionDefinition("SYSTEM_PROJECT", "PROJECT", "PROJECT_MEMBER_MANAGE", "참여자 관리", "btnProjectMember", 30, true),
                new ProgramActionDefinition("SYSTEM_PROJECT", "PROJECT", "PROJECT_OWNER_TRANSFER", "오너 이전", "btnProjectOwner", 40, true),
                new ProgramActionDefinition("SYSTEM_PROJECT", "PROJECT", "PROJECT_ARCHIVE", "프로젝트 보관", "btnProjectArchive", 50, true),
                new ProgramActionDefinition("PROJECT_WORKSPACE", "PROJECT", "PROJECT_TASK_UPDATE", "작업 변경", "btnProjectTask", 10, true),
                new ProgramActionDefinition("PROJECT_REPORT", "PROJECT", "PROJECT_REPORT_READ", "현황 조회", "btnProjectReport", 10, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_READ", "공통코드 조회", "btnCommonCodeRead", 5, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_SAVE", "공통코드 저장", "btnCommonCodeSave", 10, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_GROUP_SAVE", "코드 그룹 저장", "btnCommonCodeGroup", 20, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_DEACTIVATE", "코드 비활성화", "btnCommonCodeDeactivate", 30, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_DELETE", "코드 삭제", "btnCommonCodeDelete", 40, true),
                new ProgramActionDefinition("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_BULK_IMPORT", "코드 일괄 등록", "btnCommonCodeImport", 50, true),
                new ProgramActionDefinition("SYSTEM_AUDIT", "AUDIT", "AUDIT_READ", "감사 이력 조회", "btnAuditRead", 10, true),
                new ProgramActionDefinition("SYSTEM_AUDIT", "AUDIT", "AUDIT_EXPORT", "감사 이력 내보내기", "btnAuditExport", 20, true),
                new ProgramActionDefinition("CONTENT_LIST", "CONTENT", "CONTENT_DELETE_OLD", "폐기 기능", "btnOldDelete", 99, false))
                .forEach(this::registerAction);

        registerActionGrants(AUTH_SYSTEM_ADMIN, Set.copyOf(programActions.keySet()));
        registerActionGrants(AUTH_AUTHORITY_MANAGER, Set.of(
                grant("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_READ"),
                grant("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_UPDATE"),
                grant("SYSTEM_AUTH_REQUEST", "AUTHORITY", "AUTHORITY_REQUEST_READ"),
                grant("SYSTEM_AUTH_REQUEST", "AUTHORITY", "AUTHORITY_REQUEST_APPROVE"),
                grant("SYSTEM_AUTH_REQUEST", "AUTHORITY", "AUTHORITY_REQUEST_REJECT"),
                grant("SYSTEM_AUDIT", "AUDIT", "AUDIT_READ")));
        registerActionGrants(AUTH_PROGRAM_MANAGER, Set.of(
                grant("SYSTEM_MENU", "MENU", "MENU_READ"),
                grant("SYSTEM_MENU", "MENU", "MENU_SAVE"),
                grant("SYSTEM_MENU", "MENU", "MENU_GRANT"),
                grant("SYSTEM_MENU", "MENU", "MENU_DEACTIVATE"),
                grant("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_READ"),
                grant("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_SAVE"),
                grant("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_ACTION_SAVE"),
                grant("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_GRANT"),
                grant("SYSTEM_AUDIT", "AUDIT", "AUDIT_READ")));
        registerActionGrants(AUTH_PROJECT_MANAGER, Set.of(
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_READ"),
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_CREATE"),
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_UPDATE"),
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_MEMBER_MANAGE"),
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_OWNER_TRANSFER"),
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_ARCHIVE"),
                grant("PROJECT_WORKSPACE", "PROJECT", "PROJECT_TASK_UPDATE"),
                grant("PROJECT_REPORT", "PROJECT", "PROJECT_REPORT_READ")));
        registerActionGrants(AUTH_CODE_MANAGER, Set.of(
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_READ"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_SAVE"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_GROUP_SAVE"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_DEACTIVATE"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_DELETE"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_BULK_IMPORT"),
                grant("SYSTEM_AUDIT", "AUDIT", "AUDIT_READ")));
        registerActionGrants(AUTH_CONTENT_MANAGER, Set.of(
                grant("CONTENT_LIST", "CONTENT", "CONTENT_READ"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_SAVE"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_PUBLISH"),
                grant("CONTENT_EDITOR", "CONTENT", "CONTENT_CREATE"),
                grant("CONTENT_EDITOR", "CONTENT", "CONTENT_REVIEW"),
                grant("CONTENT_PUBLISH", "CONTENT", "CONTENT_ARCHIVE")));
        registerActionGrants(AUTH_AUDITOR, Set.of(
                grant("CONTENT_LIST", "CONTENT", "CONTENT_READ"),
                grant("CONTENT_LIST", "CONTENT", "CONTENT_EXPORT"),
                grant("SYSTEM_AUTH", "AUTHORITY", "AUTHORITY_READ"),
                grant("SYSTEM_PROGRAM", "PROGRAM", "PROGRAM_READ"),
                grant("SYSTEM_PROJECT", "PROJECT", "PROJECT_READ"),
                grant("PROJECT_REPORT", "PROJECT", "PROJECT_REPORT_READ"),
                grant("SYSTEM_CODE", "COMMON_CODE", "COMMON_CODE_READ"),
                grant("SYSTEM_AUDIT", "AUDIT", "AUDIT_READ"),
                grant("SYSTEM_AUDIT", "AUDIT", "AUDIT_EXPORT")));
        registerActionGrants(AUTH_VIEWER,
                Set.of(grant("CONTENT_LIST", "CONTENT", "CONTENT_READ")));
    }

    /**
     * Spring 기동 시 oracle 프로필의 영속화 어댑터가 있으면 데모 시드를 버리고 PM_* 원장을 로드한다.
     * demo 프로필에는 어댑터 빈이 없으므로 기존 인메모리 데이터와 공개 API가 그대로 유지된다.
     */
    @Autowired
    public AuthorizationCatalog(
            ObjectProvider<AuthorizationCatalogPersistence> persistenceProvider) {
        this();
        this.persistence = persistenceProvider.getIfAvailable();
        if (persistence != null) {
            loadPersistentSnapshot(persistence.loadSnapshot());
        }
    }

    /** Oracle 스냅샷으로 모든 카탈로그 컬렉션과 도메인 버전을 교체한다. */
    private void loadPersistentSnapshot(
            AuthorizationCatalogPersistence.CatalogSnapshot snapshot) {
        organizations.clear();
        snapshot.organizations().forEach(item -> organizations.put(item.id(), item));
        users.clear();
        snapshot.users().forEach(item -> users.put(item.username(), item));
        authorities.clear();
        snapshot.authorities().forEach(item -> authorities.put(item.id(), item));
        assignments.clear();
        assignments.addAll(snapshot.assignments());
        menus.clear();
        snapshot.menus().forEach(item -> menus.put(item.id(), item));
        authorityMenuGrants.clear();
        snapshot.menuGrants().forEach(this::registerMenuGrants);
        programs.clear();
        snapshot.programs().forEach(item -> programs.put(item.id(), item));
        programActions.clear();
        snapshot.actions().forEach(this::registerAction);
        authorityActionGrants.clear();
        snapshot.actionGrants().forEach(this::registerActionGrants);
        authorityVersion.set(snapshot.authorityVersion());
        menuVersion.set(snapshot.menuVersion());
        programVersion.set(snapshot.programVersion());
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

    /** 표시명·ID와 활성 상태로 권한 마스터를 검색한다. null 조건은 전체를 의미한다. */
    public List<AuthorityDefinition> searchAuthorities(String keyword, Boolean active) {
        return authorities().stream()
                .filter(authority -> active == null || authority.active() == active)
                .filter(authority -> matchesKeyword(keyword, authority.id(), authority.name()))
                .toList();
    }

    /** 인증 사용자명으로 현재 조직 정보가 포함된 사용자 프로필을 조회한다. */
    public Optional<UserProfile> findUser(String username) {
        return Optional.ofNullable(users.get(username));
    }

    /** 조직 ID로 조직 마스터를 조회한다. */
    public Optional<OrganizationDefinition> findOrganization(String organizationId) {
        return Optional.ofNullable(organizations.get(organizationId));
    }

    /** 상위 조직·정렬 순서가 안정적인 전체 조직 목록을 반환한다. */
    public List<OrganizationDefinition> organizations() {
        return organizations.values().stream()
                .sorted(Comparator.comparing(OrganizationDefinition::id))
                .toList();
    }

    /** 권한 관리 대상 전체 사용자 프로필을 사용자명 순서로 반환한다. */
    public List<UserProfile> users() {
        return users.values().stream()
                .sorted(Comparator.comparing(UserProfile::username))
                .toList();
    }

    /** 사용자명과 선택 조직으로 관리 대상을 검색한다. */
    public List<UserProfile> searchUsers(String keyword, String organizationId) {
        String normalizedOrganization = normalizeOptionalId(organizationId, "organizationId");
        return users().stream()
                .filter(user -> normalizedOrganization == null
                        || normalizedOrganization.equals(user.organizationId()))
                .filter(user -> matchesKeyword(keyword, user.username(), user.organizationId()))
                .toList();
    }

    /** 한 사용자의 모든 직접·위임 권한 이력을 반환한다. 최신 이력 선택은 서비스가 담당한다. */
    public List<AuthorityAssignment> assignmentsFor(String username) {
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

    /** ID·표시명, 활성·표시 조건으로 메뉴 마스터를 검색한다. */
    public List<MenuDefinition> searchMenus(String keyword, Boolean active, Boolean displayed) {
        return menus().stream()
                .filter(menu -> active == null || menu.active() == active)
                .filter(menu -> displayed == null || menu.displayed() == displayed)
                .filter(menu -> matchesKeyword(keyword, menu.id(), menu.name(), menu.path()))
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

    /** 메뉴·프로그램·검색어·활성 상태를 조합해 기능 마스터를 조회한다. */
    public List<ProgramActionDefinition> searchProgramActions(
            String menuId, String programId, String keyword, Boolean active) {
        String normalizedMenuId = normalizeOptionalId(menuId, "menuId");
        String normalizedProgramId = normalizeOptionalId(programId, "programId");
        return programActions().stream()
                .filter(action -> normalizedMenuId == null || action.menuId().equals(normalizedMenuId))
                .filter(action -> normalizedProgramId == null
                        || action.programId().equals(normalizedProgramId))
                .filter(action -> active == null || action.active() == active)
                .filter(action -> matchesKeyword(keyword, action.actionId(), action.label(),
                        action.componentId()))
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

    /** ID·표시명·설명과 활성 상태로 프로그램을 검색한다. */
    public List<ProgramDefinition> searchPrograms(String keyword, Boolean active) {
        return programs().stream()
                .filter(program -> active == null || program.active() == active)
                .filter(program -> matchesKeyword(
                        keyword, program.id(), program.name(), program.description()))
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
        AuthorityDefinition saved = new AuthorityDefinition(authorityId, name, request.active());
        AuthorityDefinition previous = authorities.get(authorityId);
        if (saved.equals(previous)) {
            return saved;
        }
        if (persistence != null) {
            persistence.saveAuthority(saved);
        }
        authorities.put(authorityId, saved);
        authorityVersion.incrementAndGet();
        return saved;
    }

    /** 비활성·미사용 권한만 물리 삭제하고 연결된 메뉴·기능 매핑을 함께 정리한다. */
    public synchronized void deleteAuthority(String authorityId) {
        String normalizedId = normalizeId(authorityId, "authorityId");
        AuthorityDefinition authority = authorities.get(normalizedId);
        if (authority == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedId);
        }
        if (AUTH_SYSTEM_ADMIN.equals(normalizedId) || authority.active()
                || assignments.stream().anyMatch(item -> item.authorityId().equals(normalizedId))) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedId);
        }
        boolean menuMappingsRemoved = authorityMenuGrants.containsKey(normalizedId);
        boolean actionMappingsRemoved = authorityActionGrants.containsKey(normalizedId);
        if (persistence != null) {
            persistence.deleteAuthority(
                    normalizedId, menuMappingsRemoved, actionMappingsRemoved);
        }
        authorities.remove(normalizedId);
        authorityMenuGrants.remove(normalizedId);
        authorityActionGrants.remove(normalizedId);
        authorityVersion.incrementAndGet();
        if (menuMappingsRemoved) {
            menuVersion.incrementAndGet();
        }
        if (actionMappingsRemoved) {
            programVersion.incrementAndGet();
        }
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
                programId, name, description, request.active(), request.screen() != null ? request.screen()
                        : Optional.ofNullable(programs.get(programId)).map(ProgramDefinition::screen).orElse(ProgramScreen.NONE));
        ProgramDefinition previous = programs.get(programId);
        if (saved.equals(previous)) {
            return saved;
        }
        if (persistence != null) {
            persistence.saveProgram(saved);
        }
        programs.put(programId, saved);
        programVersion.incrementAndGet();
        return saved;
    }

    /** 비활성 프로그램과 그 기능을 삭제하고 모든 권한-기능 고아 매핑을 원자적으로 정리한다. */
    public synchronized void deleteProgram(String programId) {
        String normalizedId = normalizeId(programId, "programId");
        ProgramDefinition program = programs.get(normalizedId);
        if (program == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedId);
        }
        if ("AUTHORITY".equals(normalizedId) || program.active()) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedId);
        }
        Set<ProgramActionKey> removedKeys = programActions.keySet().stream()
                .filter(key -> key.programId().equals(normalizedId))
                .collect(java.util.stream.Collectors.toSet());
        if (persistence != null) {
            persistence.deleteProgram(normalizedId);
        }
        programActions.keySet().removeAll(removedKeys);
        authorityActionGrants.values().forEach(grants -> grants.removeAll(removedKeys));
        programs.remove(normalizedId);
        programVersion.incrementAndGet();
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
        if (request.active()
                && (!menus.get(menuId).active() || !programs.get(programId).active())) {
            throw new ApiException(ErrorCode.CONFLICT, actionId);
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
        ProgramActionDefinition previous = programActions.get(key);
        if (saved.equals(previous)) {
            return saved;
        }
        if (persistence != null) {
            persistence.saveAction(saved);
        }
        programActions.put(key, saved);
        programVersion.incrementAndGet();
        return saved;
    }

    /** 비활성 기능만 삭제하고 모든 권한에 남은 기능 매핑을 함께 제거한다. */
    public synchronized void deleteAction(ProgramActionKey actionKey) {
        ProgramActionKey normalizedKey = normalizeActionKey(actionKey);
        ProgramActionDefinition action = programActions.get(normalizedKey);
        if (action == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedKey.actionId());
        }
        if (action.active() || isCoreAction(normalizedKey)) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedKey.actionId());
        }
        if (persistence != null) {
            persistence.deleteAction(normalizedKey);
        }
        programActions.remove(normalizedKey);
        authorityActionGrants.values().forEach(grants -> grants.remove(normalizedKey));
        programVersion.incrementAndGet();
    }

    /** 권한과 프로그램 기능 사이의 직접 허용 매핑을 추가하거나 제거한다. */
    public synchronized Set<ProgramActionKey> setActionGrant(
            String authorityId,
            ProgramActionKey actionKey,
            boolean granted) {
        String normalizedAuthorityId = normalizeId(authorityId, "authorityId");
        ProgramActionKey normalizedKey = normalizeActionKey(actionKey);
        if (!authorities.containsKey(normalizedAuthorityId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedAuthorityId);
        }
        if (!programActions.containsKey(normalizedKey)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedKey.actionId());
        }
        if (granted && (!authorities.get(normalizedAuthorityId).active()
                || !programActions.get(normalizedKey).active()
                || !programs.get(normalizedKey.programId()).active()
                || !menus.get(normalizedKey.menuId()).active())) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedKey.actionId());
        }
        if (AUTH_SYSTEM_ADMIN.equals(normalizedAuthorityId)
                && isCoreAction(normalizedKey) && !granted) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedKey.actionId());
        }
        Set<ProgramActionKey> grants = authorityActionGrants.computeIfAbsent(
                normalizedAuthorityId, ignored -> ConcurrentHashMap.newKeySet());
        boolean changed = granted != grants.contains(normalizedKey);
        if (!changed) {
            return Set.copyOf(grants);
        }
        if (persistence != null) {
            persistence.setActionGrant(normalizedAuthorityId, normalizedKey, granted);
        }
        if (granted) {
            grants.add(normalizedKey);
        } else {
            grants.remove(normalizedKey);
        }
        programVersion.incrementAndGet();
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
        if (type == null) {
            throw new IllegalArgumentException("type은 필수입니다.");
        }
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
        if (persistence != null) {
            persistence.insertAssignment(saved);
        }
        updateCacheAfterCommit(() -> {
            assignments.add(saved);
            authorityVersion.incrementAndGet();
        });
        return saved;
    }

    /** 같은 사용자·권한·부여 유형의 최신 이력을 REVOKED 상태로 추가한다. */
    public synchronized AuthorityAssignment revokeAssignment(
            String username,
            String authorityId,
            AssignmentType type) {
        if (type == null) {
            throw new IllegalArgumentException("type은 필수입니다.");
        }
        UserProfile user = findUser(username)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username));
        String normalizedAuthorityId = normalizeId(authorityId, "authorityId");
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
        if (AUTH_SYSTEM_ADMIN.equals(normalizedAuthorityId)
                && type == AssignmentType.DIRECT
                && countApprovedSystemAdministratorsExcept(username) == 0) {
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
        if (persistence != null) {
            persistence.insertAssignment(revoked);
        }
        updateCacheAfterCommit(() -> {
            assignments.add(revoked);
            authorityVersion.incrementAndGet();
        });
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
        MenuDefinition previous = menus.get(menuId);
        MenuArea area = request.area() != null ? request.area()
                : previous != null ? previous.area()
                : parentId != null ? menus.get(parentId).area() : MenuArea.USER;
        if (parentId != null && menus.get(parentId).area() != area) {
            throw new ApiException(ErrorCode.CONFLICT, "상위 메뉴와 같은 화면 영역을 선택해야 합니다.");
        }
        if (menus.values().stream().anyMatch(menu -> menuId.equals(menu.parentId()) && menu.area() != area)) {
            throw new ApiException(ErrorCode.CONFLICT, "하위 메뉴가 있는 메뉴의 화면 영역은 변경할 수 없습니다.");
        }
        if ("SYSTEM_AUTH".equals(menuId) && !request.active()) {
            throw new ApiException(ErrorCode.CONFLICT, menuId);
        }
        if (!request.active() && menus.values().stream()
                .anyMatch(menu -> menuId.equals(menu.parentId()) && menu.active())) {
            throw new ApiException(ErrorCode.CONFLICT, menuId);
        }
        validateMenuParent(menuId, parentId);
        MenuDefinition saved = new MenuDefinition(
                menuId,
                parentId,
                name,
                path,
                normalizeSort(request.sortOrder()),
                request.active(),
                request.displayed(),
                request.publicMenu(), area);
        if (saved.equals(previous)) {
            return saved;
        }
        if (persistence != null) {
            persistence.saveMenu(saved);
        }
        menus.put(menuId, saved);
        menuVersion.incrementAndGet();
        return saved;
    }

    /** 비활성 말단 메뉴만 삭제하고 메뉴·기능 권한의 모든 고아 매핑을 정리한다. */
    public synchronized void deleteMenu(String menuId) {
        String normalizedId = normalizeId(menuId, "menuId");
        MenuDefinition menu = menus.get(normalizedId);
        if (menu == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedId);
        }
        if (Set.of("HOME", "SYSTEM", "SYSTEM_AUTH").contains(normalizedId)
                || menu.active()
                || menus.values().stream().anyMatch(item -> normalizedId.equals(item.parentId()))) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedId);
        }
        Set<ProgramActionKey> removedKeys = programActions.keySet().stream()
                .filter(key -> key.menuId().equals(normalizedId))
                .collect(java.util.stream.Collectors.toSet());
        if (persistence != null) {
            persistence.deleteMenu(normalizedId, !removedKeys.isEmpty());
        }
        menus.remove(normalizedId);
        authorityMenuGrants.values().forEach(grants -> grants.remove(normalizedId));
        programActions.keySet().removeAll(removedKeys);
        authorityActionGrants.values().forEach(grants -> grants.removeAll(removedKeys));
        menuVersion.incrementAndGet();
        if (!removedKeys.isEmpty()) {
            programVersion.incrementAndGet();
        }
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
        if (granted && (!authorities.get(normalizedAuthorityId).active()
                || !menus.get(normalizedMenuId).active())) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedMenuId);
        }
        if (AUTH_SYSTEM_ADMIN.equals(normalizedAuthorityId)
                && "SYSTEM_AUTH".equals(normalizedMenuId) && !granted) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedMenuId);
        }
        Set<String> grants = authorityMenuGrants.computeIfAbsent(
                normalizedAuthorityId, ignored -> ConcurrentHashMap.newKeySet());
        boolean changed = granted != grants.contains(normalizedMenuId);
        if (!changed) {
            return Set.copyOf(grants);
        }
        if (persistence != null) {
            persistence.setMenuGrant(normalizedAuthorityId, normalizedMenuId, granted);
        }
        if (granted) {
            grants.add(normalizedMenuId);
        } else {
            grants.remove(normalizedMenuId);
        }
        menuVersion.incrementAndGet();
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

    /** 외부 Oracle 트랜잭션이 있으면 커밋 이후에만 인메모리 조회 스냅샷을 갱신한다. */
    private void updateCacheAfterCommit(Runnable update) {
        if (persistence != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            update.run();
                        }
                    });
            return;
        }
        update.run();
    }

    /** 회수 대상 외에 최신 승인 상태의 직접 시스템 관리자가 몇 명 남는지 계산한다. */
    private long countApprovedSystemAdministratorsExcept(String excludedUsername) {
        return users.values().stream()
                .filter(user -> !user.username().equals(excludedUsername))
                .filter(user -> assignments.stream()
                        .filter(item -> item.username().equals(user.username()))
                        .filter(item -> item.organizationId().equals(user.organizationId()))
                        .filter(item -> item.authorityId().equals(AUTH_SYSTEM_ADMIN))
                        .filter(item -> item.type() == AssignmentType.DIRECT)
                        .max(Comparator.comparingLong(AuthorityAssignment::sequence))
                        .map(item -> item.status() == AssignmentStatus.APPROVED)
                        .orElse(false))
                .count();
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

    /** 외부에서 받은 기능 복합 키의 세 식별자를 동일한 규칙으로 정규화한다. */
    private ProgramActionKey normalizeActionKey(ProgramActionKey key) {
        if (key == null) {
            throw new IllegalArgumentException("actionKey는 필수입니다.");
        }
        return new ProgramActionKey(
                normalizeId(key.menuId(), "menuId"),
                normalizeId(key.programId(), "programId"),
                normalizeId(key.actionId(), "actionId"));
    }

    /** 검색어가 없으면 전체를, 있으면 후보 문자열 중 하나에 포함되는 항목을 선택한다. */
    private boolean matchesKeyword(String keyword, String... values) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
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
        return direct(username, "HQ", authorityId);
    }

    /** 지정 조직에서 승인된 직접 권한 이력을 만드는 초기 데이터 보조 메서드다. */
    private static AuthorityAssignment direct(
            String username, String organizationId, String authorityId) {
        return new AuthorityAssignment(username, organizationId, authorityId, AssignmentType.DIRECT,
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
    public record AuthorityDefinition(String id, String name, boolean active) {
    }

    /** 프로그램 마스터의 식별자, 표시명, 설명과 활성 상태다. */
    public record ProgramDefinition(
            String id,
            String name,
            String description,
            boolean active,
            ProgramScreen screen) {
        public ProgramDefinition(String id, String name, String description, boolean active) {
            this(id, name, description, active, ProgramScreen.NONE);
        }
    }

    /** 실제 구현된 사용자 화면만 등록 프로그램에 연결한다. 외부 URL이나 임의 코드는 실행하지 않는다. */
    public enum ProgramScreen {
        NONE("연결 안 함", null, null),
        CONTENT_DEMO("콘텐츠 업무 예제", "CONTENT_LIST", "#content"),
        AUTHORITY_REQUEST("내 권한 신청", "MY_AUTH_REQUEST", "#authority-request");

        private final String label;
        private final String menuId;
        private final String path;
        ProgramScreen(String label, String menuId, String path) {
            this.label = label;
            this.menuId = menuId;
            this.path = path;
        }
        public String label() { return label; }
        public String menuId() { return menuId; }
        public String path() { return path; }
    }

    public enum MenuArea { USER, ADMIN }

    /** 사용자의 현재 조직을 포함한 최소 프로필이다. */
    public record UserProfile(String username, String organizationId) {
    }

    /** 사용자 검색과 조직 범위 선택에 사용하는 계층형 조직 마스터다. */
    public record OrganizationDefinition(
            String id, String name, String parentId, boolean active) {
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
            boolean publicMenu,
            MenuArea area) {
        /** 기존 호출은 저장 시 기존 영역 또는 상위 메뉴 영역을 유지한다. */
        public MenuDefinition(String id, String parentId, String name, String path, int sortOrder,
                boolean active, boolean displayed, boolean publicMenu) {
            this(id, parentId, name, path, sortOrder, active, displayed, publicMenu, null);
        }
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
}
