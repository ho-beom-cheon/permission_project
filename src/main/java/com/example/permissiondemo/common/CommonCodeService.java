package com.example.permissiondemo.common;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 재시작 시 초기화되는 계층형 인메모리 공통코드 저장소다.
 * 실제 적용 시 저장소만 Repository로 교체하고 조회·버전 계약은 유지할 수 있다.
 */
@Service
public class CommonCodeService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9_]{1,30}");
    private static final LocalDate LONG_AGO = LocalDate.of(2000, 1, 1);
    private static final LocalDate FAR_FUTURE = LocalDate.of(2099, 12, 31);

    private final ConcurrentMap<String, ConcurrentMap<String, CommonCodeItem>> groups =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CommonCodeGroup> groupDefinitions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentMap<QueryKey, List<CommonCodeItem>> viewCache =
            new ConcurrentHashMap<>();
    private final AuditEventService auditEventService;
    private final Clock clock;
    private final CommonCodePersistence persistence;

    /** 단위 테스트에서 Spring 컨텍스트 없이 사용할 수 있도록 기본 감사 서비스와 UTC Clock을 구성한다. */
    public CommonCodeService() {
        this(new AuditEventService(), Clock.systemUTC(), new InMemoryCommonCodePersistence());
    }

    /**
     * 실제 애플리케이션에서 공통 감사 서비스와 서울 시간대 Clock을 주입받고 데모 코드를 등록한다.
     * REGION 그룹은 상위 시·도와 하위 시·군·구 관계를 시험하기 위한 계층 예시다.
     */
    public CommonCodeService(AuditEventService auditEventService, Clock clock) {
        this(auditEventService, clock, new InMemoryCommonCodePersistence());
    }

    /** 활성 프로필의 저장 전략에서 초기 원본을 읽고 이후 변경을 같은 저장소에 반영한다. */
    @Autowired
    public CommonCodeService(
            AuditEventService auditEventService,
            Clock clock,
            CommonCodePersistence persistence) {
        this.auditEventService = auditEventService;
        this.clock = clock;
        this.persistence = persistence;
        if (persistence.persistent()) {
            loadPersistentData();
        } else {
            initializeDemoData();
        }
    }

    private void initializeDemoData() {
        registerGroup(new CommonCodeGroup("USE_YN", "사용 여부", "전역 사용 여부 선택값", 10, true, true));
        registerGroup(new CommonCodeGroup("ARTICLE_STATUS", "콘텐츠 상태", "콘텐츠 작성·게시 상태", 20, true, true));
        registerGroup(new CommonCodeGroup("AUTH_STATUS", "권한 상태", "권한 요청·승인·회수 상태", 30, true, true));
        registerGroup(new CommonCodeGroup("AUTH_TYPE", "권한 부여 유형", "직접 부여와 위임 구분", 40, true, true));
        registerGroup(new CommonCodeGroup("PROJECT_STATUS", "프로젝트 상태", "프로젝트 생명주기 상태", 50, true, true));
        registerGroup(new CommonCodeGroup("PROJECT_ROLE", "프로젝트 역할", "프로젝트 참여자 역할", 60, true, true));
        registerGroup(new CommonCodeGroup("PROGRAM_TYPE", "프로그램 유형", "화면·배치·API 프로그램 구분", 70, true, true));
        registerGroup(new CommonCodeGroup("REGION", "지역", "시·도와 시·군·구 계층 코드", 80, true, true));
        register("USE_YN", item("Y", "사용", null, 10, true));
        register("USE_YN", item("N", "미사용", null, 20, true));
        register("ARTICLE_STATUS", item("DRAFT", "작성 중", null, 10, true));
        register("ARTICLE_STATUS", item("PUBLISHED", "게시", null, 20, true));
        register("ARTICLE_STATUS", item("DELETED", "삭제", null, 30, false));
        register("AUTH_STATUS", item("PENDING", "승인 대기", null, 10, true));
        register("AUTH_STATUS", item("APPROVED", "승인", null, 20, true));
        register("AUTH_STATUS", item("REJECTED", "반려", null, 30, true));
        register("AUTH_STATUS", item("CANCELLED", "취소", null, 40, true));
        register("AUTH_STATUS", item("REVOKED", "회수", null, 50, true));
        register("AUTH_STATUS", item("EXPIRED", "만료", null, 60, true));
        register("AUTH_TYPE", item("DIRECT", "직접 부여", null, 10, true));
        register("AUTH_TYPE", item("DELEGATED", "위임", null, 20, true));
        register("PROJECT_STATUS", item("PLANNED", "계획", null, 10, true));
        register("PROJECT_STATUS", item("ACTIVE", "진행", null, 20, true));
        register("PROJECT_STATUS", item("ON_HOLD", "보류", null, 30, true));
        register("PROJECT_STATUS", item("COMPLETED", "완료", null, 40, true));
        register("PROJECT_STATUS", item("ARCHIVED", "보관", null, 50, true));
        register("PROJECT_ROLE", item("OWNER", "오너", null, 10, true));
        register("PROJECT_ROLE", item("MANAGER", "관리자", null, 20, true));
        register("PROJECT_ROLE", item("MEMBER", "참여자", null, 30, true));
        register("PROJECT_ROLE", item("VIEWER", "조회자", null, 40, true));
        register("PROGRAM_TYPE", item("SCREEN", "화면", null, 10, true));
        register("PROGRAM_TYPE", item("API", "API", null, 20, true));
        register("PROGRAM_TYPE", item("BATCH", "배치", null, 30, true));
        register("REGION", item("SEOUL", "서울", null, 10, true));
        register("REGION", item("BUSAN", "부산", null, 20, true));
        register("REGION", item("GANGNAM", "강남구", "SEOUL", 10, true));
        register("REGION", item("MAPO", "마포구", "SEOUL", 20, true));
        register("REGION", item("HAEUNDAE", "해운대구", "BUSAN", 10, true));
    }

    /** Oracle 원본을 서비스의 동시성 안전 조회 상태로 구성한다. */
    private void loadPersistentData() {
        List<CommonCodePersistence.StoredGroup> storedGroups = persistence.load();
        if (storedGroups.isEmpty()) {
            throw new IllegalStateException("Oracle 공통코드 기준정보가 비어 있습니다.");
        }
        for (CommonCodePersistence.StoredGroup stored : storedGroups) {
            CommonCodeGroup group = stored.group();
            groupDefinitions.put(group.groupCode(), group);
            ConcurrentMap<String, CommonCodeItem> items = new ConcurrentHashMap<>();
            stored.items().forEach(item -> items.put(item.code(), item));
            groups.put(group.groupCode(), items);
            versions.put(group.groupCode(), new AtomicLong(stored.version()));
        }
    }

    /** 전체 코드 그룹을 관리 화면 정렬 순서로 반환한다. */
    public List<CommonCodeGroup> groups() {
        return groupDefinitions.values().stream()
                .sorted(Comparator.comparingInt(CommonCodeGroup::sortOrder)
                        .thenComparing(CommonCodeGroup::groupCode))
                .toList();
    }

    /** 그룹 코드·표시명·설명과 활성 조건으로 코드 그룹을 검색한다. */
    public List<CommonCodeGroup> searchGroups(String keyword, Boolean active) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return groups().stream()
                .filter(group -> active == null || group.active() == active)
                .filter(group -> normalizedKeyword.isEmpty()
                        || group.groupCode().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || group.name().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || group.description().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .toList();
    }

    /** 코드 그룹을 신규 등록하거나 메타데이터와 활성 상태를 수정한다. */
    public synchronized CommonCodeGroup saveGroup(
            String groupCode,
            String name,
            String description,
            Integer sortOrder,
            Boolean active) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        validateName(name);
        String normalizedDescription = normalizeDescription(description);
        int resolvedSortOrder = validateSortOrder(sortOrder);
        CommonCodeGroup previous = groupDefinitions.get(normalizedGroup);
        boolean system = previous != null && previous.system();
        CommonCodeGroup saved = new CommonCodeGroup(
                normalizedGroup,
                name.trim(),
                normalizedDescription,
                resolvedSortOrder,
                active == null || active,
                system);
        if (saved.equals(previous)) {
            return saved;
        }
        long changedVersion = versions.getOrDefault(
                normalizedGroup, new AtomicLong()).get() + 1;
        persistence.saveGroup(saved, changedVersion);
        groupDefinitions.put(normalizedGroup, saved);
        groups.computeIfAbsent(normalizedGroup, ignored -> new ConcurrentHashMap<>());
        versions.computeIfAbsent(normalizedGroup, ignored -> new AtomicLong())
                .set(changedVersion);
        invalidateGroup(normalizedGroup);
        auditEventService.record(
                "COMMON_CODE_GROUP_SAVED",
                "COMMON_CODE_GROUP",
                normalizedGroup,
                "SUCCESS",
                Map.of("changeType", previous == null ? "CREATED" : "UPDATED",
                        "groupVersion", changedVersion));
        return saved;
    }

    /** 사용자 정의 그룹만 비활성·빈 상태에서 삭제할 수 있다. */
    public synchronized void deleteGroup(String groupCode) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        CommonCodeGroup group = findGroupDefinition(normalizedGroup);
        if (group.system() || group.active() || !findGroup(normalizedGroup).isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedGroup);
        }
        persistence.deleteGroup(normalizedGroup);
        groupDefinitions.remove(normalizedGroup);
        groups.remove(normalizedGroup);
        versions.remove(normalizedGroup);
        invalidateGroup(normalizedGroup);
        auditEventService.record(
                "COMMON_CODE_GROUP_DELETED",
                "COMMON_CODE_GROUP",
                normalizedGroup,
                "SUCCESS",
                Map.of());
    }

    /** 현재 날짜에 선택 가능한 활성 코드 전체를 표시 순서로 반환한다. */
    public List<CommonCodeItem> findActiveItems(String groupCode) {
        return findItems(groupCode, true, null);
    }

    /**
     * 그룹의 코드를 활성 조건과 선택적 상위 코드 조건으로 조회한다.
     * 같은 조건의 반복 조회는 캐시된 불변 목록을 반환한다.
     */
    public List<CommonCodeItem> findItems(
            String groupCode,
            boolean activeOnly,
            String parentCode) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        CommonCodeGroup group = findGroupDefinition(normalizedGroup);
        findGroup(normalizedGroup);
        String normalizedParent = normalizeOptional(parentCode, "parentCode");
        if (activeOnly && !group.active()) {
            return List.of();
        }
        QueryKey queryKey = new QueryKey(normalizedGroup, activeOnly, normalizedParent);
        return viewCache.computeIfAbsent(queryKey, ignored -> loadItems(
                normalizedGroup, activeOnly, normalizedParent));
    }

    /** 코드 목록과 그룹 버전을 함께 반환해 클라이언트가 캐시 갱신 여부를 판단할 수 있게 한다. */
    public CodeGroupView findGroupView(
            String groupCode,
            boolean activeOnly,
            String parentCode) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        return new CodeGroupView(
                normalizedGroup,
                version(normalizedGroup),
                findItems(normalizedGroup, activeOnly, parentCode));
    }

    /** 계층·기간을 사용하지 않는 단순 저장 요청을 전체 저장 메서드에 위임한다. */
    public synchronized CommonCodeItem saveItem(
            String groupCode,
            String code,
            String name,
            Integer sortOrder,
            Boolean active) {
        return saveItem(groupCode, code, name, null, sortOrder, active, null, null);
    }

    /**
     * 공통코드를 신규 등록하거나 같은 코드의 기존 값을 갱신한다.
     * 입력 정규화, 기간·상위 관계 검증, 버전 증가, 조회 캐시 무효화와 감사 기록을 한 흐름으로 처리한다.
     */
    public synchronized CommonCodeItem saveItem(
            String groupCode,
            String code,
            String name,
            String parentCode,
            Integer sortOrder,
            Boolean active,
            LocalDate validFrom,
            LocalDate validTo) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        CommonCodeGroup group = findGroupDefinition(normalizedGroup);
        if (!group.active()) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedGroup);
        }
        ConcurrentMap<String, CommonCodeItem> items = findGroup(normalizedGroup);
        String normalizedCode = normalizeCode(code, "code");
        String normalizedParent = normalizeOptional(parentCode, "parentCode");
        validateName(name);
        int resolvedSortOrder = validateSortOrder(sortOrder);
        LocalDate resolvedFrom = validFrom == null ? LONG_AGO : validFrom;
        LocalDate resolvedTo = validTo == null ? FAR_FUTURE : validTo;
        validatePeriod(resolvedFrom, resolvedTo);
        validateParent(items, normalizedCode, normalizedParent);
        if ((active == null || active) && normalizedParent != null
                && !items.get(normalizedParent).active()) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedParent);
        }

        // 모든 검증이 끝난 뒤 저장해 실패 요청이 기존 값을 일부 변경하지 않게 한다.
        CommonCodeItem saved = new CommonCodeItem(
                normalizedCode,
                name.trim(),
                normalizedParent,
                resolvedSortOrder,
                active == null || active,
                resolvedFrom,
                resolvedTo);
        CommonCodeItem previous = items.get(normalizedCode);
        if (saved.equals(previous)) {
            return saved;
        }
        ConcurrentMap<String, CommonCodeItem> changedItems = new ConcurrentHashMap<>(items);
        changedItems.put(normalizedCode, saved);
        int cascadedCount = saved.active()
                ? 0 : deactivateDescendants(changedItems, normalizedCode);
        long changedVersion = versions.getOrDefault(
                normalizedGroup, new AtomicLong()).get() + 1;
        persistence.saveItems(group, List.copyOf(changedItems.values()), changedVersion);
        groups.put(normalizedGroup, changedItems);
        versions.computeIfAbsent(normalizedGroup, ignored -> new AtomicLong())
                .set(changedVersion);
        // 같은 그룹을 조회한 모든 활성·상위 코드 조합을 제거해 다음 요청에서 최신 목록을 계산한다.
        invalidateGroup(normalizedGroup);
        auditEventService.record(
                "COMMON_CODE_SAVED",
                "COMMON_CODE",
                normalizedGroup + ":" + normalizedCode,
                "SUCCESS",
                Map.of(
                        "changeType", previous == null ? "CREATED" : "UPDATED",
                        "groupVersion", changedVersion,
                        "cascadedCount", cascadedCount));
        return saved;
    }

    /** 최대 100개 항목을 모든 검증 후 한 버전으로 저장해 부분 반영을 방지한다. */
    public synchronized BulkSaveResult saveItemsBulk(
            String groupCode,
            List<CommonCodeItem> requestedItems) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        CommonCodeGroup group = findGroupDefinition(normalizedGroup);
        if (!group.active()) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedGroup);
        }
        if (requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 100) {
            throw new IllegalArgumentException("일괄 저장 항목은 1~100건이어야 합니다.");
        }

        ConcurrentMap<String, CommonCodeItem> current = findGroup(normalizedGroup);
        ConcurrentMap<String, CommonCodeItem> changedItems = new ConcurrentHashMap<>(current);
        Set<String> requestedCodes = new LinkedHashSet<>();
        for (CommonCodeItem request : requestedItems) {
            if (request == null) {
                throw new IllegalArgumentException("공통코드 항목은 null일 수 없습니다.");
            }
            String code = normalizeCode(request.code(), "code");
            if (!requestedCodes.add(code)) {
                throw new IllegalArgumentException("일괄 저장에 중복 코드가 있습니다: " + code);
            }
            String parentCode = normalizeOptional(request.parentCode(), "parentCode");
            validateName(request.name());
            int sortOrder = validateSortOrder(request.sortOrder());
            LocalDate validFrom = request.validFrom() == null ? LONG_AGO : request.validFrom();
            LocalDate validTo = request.validTo() == null ? FAR_FUTURE : request.validTo();
            validatePeriod(validFrom, validTo);
            changedItems.put(code, new CommonCodeItem(
                    code, request.name().trim(), parentCode, sortOrder, request.active(),
                    validFrom, validTo));
        }

        // 입력 순서와 무관하게 모든 후보를 먼저 구성한 뒤 계층과 활성 부모 조건을 검증한다.
        for (String code : requestedCodes) {
            CommonCodeItem item = changedItems.get(code);
            validateParent(changedItems, code, item.parentCode());
            if (item.active() && item.parentCode() != null
                    && !changedItems.get(item.parentCode()).active()) {
                throw new ApiException(ErrorCode.CONFLICT, item.parentCode());
            }
        }

        int cascadedCount = 0;
        for (String code : requestedCodes) {
            if (!changedItems.get(code).active()) {
                cascadedCount += deactivateDescendants(changedItems, code);
            }
        }
        long changedCount = changedItems.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(current.get(entry.getKey())))
                .count();
        long currentVersion = versions.getOrDefault(normalizedGroup, new AtomicLong()).get();
        if (changedCount == 0) {
            return new BulkSaveResult(
                    normalizedGroup, currentVersion, 0, 0,
                    requestedCodes.stream().map(changedItems::get).toList());
        }

        long changedVersion = currentVersion + 1;
        persistence.saveItems(group, List.copyOf(changedItems.values()), changedVersion);
        groups.put(normalizedGroup, changedItems);
        versions.computeIfAbsent(normalizedGroup, ignored -> new AtomicLong())
                .set(changedVersion);
        invalidateGroup(normalizedGroup);
        auditEventService.record(
                "COMMON_CODE_BULK_SAVED",
                "COMMON_CODE_GROUP",
                normalizedGroup,
                "SUCCESS",
                Map.of(
                        "requestedCount", requestedItems.size(),
                        "changedCount", changedCount,
                        "cascadedCount", cascadedCount,
                        "groupVersion", changedVersion));
        return new BulkSaveResult(
                normalizedGroup, changedVersion, (int) changedCount, cascadedCount,
                requestedCodes.stream().map(changedItems::get).toList());
    }

    /** 코드명·코드값, 활성 상태와 선택 상위 코드로 한 그룹의 항목을 검색한다. */
    public List<CommonCodeItem> searchItems(
            String groupCode, String keyword, Boolean active, String parentCode) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        String normalizedParent = normalizeOptional(parentCode, "parentCode");
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return findGroup(normalizedGroup).values().stream()
                .filter(item -> normalizedParent == null || normalizedParent.equals(item.parentCode()))
                .filter(item -> active == null || item.active() == active)
                .filter(item -> normalizedKeyword.isEmpty()
                        || item.code().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || item.name().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .sorted(Comparator.comparingInt(CommonCodeItem::sortOrder)
                        .thenComparing(CommonCodeItem::code))
                .toList();
    }

    /** 선택 코드와 모든 하위 코드를 비활성화한다. */
    public synchronized CommonCodeItem deactivateItem(String groupCode, String code) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        String normalizedCode = normalizeCode(code, "code");
        CommonCodeItem current = findGroup(normalizedGroup).get(normalizedCode);
        if (current == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedCode);
        }
        return saveItem(
                normalizedGroup,
                current.code(),
                current.name(),
                current.parentCode(),
                current.sortOrder(),
                false,
                current.validFrom(),
                current.validTo());
    }

    /** 비활성 말단 코드만 삭제해 활성 참조나 계층 고아가 생기지 않게 한다. */
    public synchronized void deleteItem(String groupCode, String code) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        String normalizedCode = normalizeCode(code, "code");
        ConcurrentMap<String, CommonCodeItem> items = findGroup(normalizedGroup);
        CommonCodeGroup group = findGroupDefinition(normalizedGroup);
        CommonCodeItem current = items.get(normalizedCode);
        if (current == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, normalizedCode);
        }
        if (group.system() || current.active() || items.values().stream()
                .anyMatch(item -> normalizedCode.equals(item.parentCode()))) {
            throw new ApiException(ErrorCode.CONFLICT, normalizedCode);
        }
        long changedVersion = versions.get(normalizedGroup).get() + 1;
        persistence.deleteItem(normalizedGroup, normalizedCode, changedVersion);
        items.remove(normalizedCode);
        versions.get(normalizedGroup).set(changedVersion);
        invalidateGroup(normalizedGroup);
        auditEventService.record(
                "COMMON_CODE_DELETED",
                "COMMON_CODE",
                normalizedGroup + ":" + normalizedCode,
                "SUCCESS",
                Map.of("groupVersion", changedVersion));
    }

    /** 그룹별 등록 코드 수를 관리 화면용 요약 정보로 반환한다. */
    public Map<String, Integer> groupSummary() {
        return groups.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().size()));
    }

    /** 전체 공통코드 그룹의 현재 변경 버전을 불변 Map으로 반환한다. */
    public Map<String, Long> groupVersions() {
        return versions.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().get()));
    }

    /** 캐시에 값이 없을 때 현재 날짜와 조건을 적용해 실제 코드 목록을 계산한다. */
    private List<CommonCodeItem> loadItems(
            String groupCode,
            boolean activeOnly,
            String parentCode) {
        LocalDate today = LocalDate.now(clock);
        return findGroup(groupCode).values().stream()
                .filter(item -> parentCode == null || parentCode.equals(item.parentCode()))
                .filter(item -> !activeOnly || item.isSelectableOn(today))
                .sorted(Comparator.comparingInt(CommonCodeItem::sortOrder)
                        .thenComparing(CommonCodeItem::code))
                .toList();
    }

    /** 정규화된 그룹 코드로 저장소를 조회하고 존재하지 않으면 표준 404 예외를 발생시킨다. */
    private ConcurrentMap<String, CommonCodeItem> findGroup(String groupCode) {
        ConcurrentMap<String, CommonCodeItem> items = groups.get(groupCode);
        if (items == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, groupCode);
        }
        return items;
    }

    /** 그룹 메타데이터를 조회하고 존재하지 않으면 표준 404 예외를 발생시킨다. */
    private CommonCodeGroup findGroupDefinition(String groupCode) {
        CommonCodeGroup group = groupDefinitions.get(groupCode);
        if (group == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, groupCode);
        }
        return group;
    }

    /** 지정 그룹의 현재 버전을 반환한다. 그룹 존재 여부도 함께 검증한다. */
    private long version(String groupCode) {
        findGroupDefinition(groupCode);
        findGroup(groupCode);
        return versions.getOrDefault(groupCode, new AtomicLong()).get();
    }

    /** 초기 그룹 메타데이터와 빈 항목 저장소·버전 원장을 함께 등록한다. */
    private void registerGroup(CommonCodeGroup group) {
        groupDefinitions.put(group.groupCode(), group);
        groups.putIfAbsent(group.groupCode(), new ConcurrentHashMap<>());
        versions.putIfAbsent(group.groupCode(), new AtomicLong());
    }

    /** 초기 데모 코드를 등록하면서 해당 그룹 버전을 증가시킨다. */
    private void register(String groupCode, CommonCodeItem item) {
        findGroupDefinition(groupCode);
        findGroup(groupCode).put(item.code(), item);
        versions.computeIfAbsent(groupCode, ignored -> new AtomicLong()).incrementAndGet();
    }

    /** 한 그룹의 모든 조회 조건 캐시를 무효화한다. */
    private void invalidateGroup(String groupCode) {
        viewCache.keySet().removeIf(key -> key.groupCode().equals(groupCode));
    }

    /** 비활성화된 부모 아래의 활성 후손을 모두 같은 변경 단위에서 비활성화한다. */
    private int deactivateDescendants(
            ConcurrentMap<String, CommonCodeItem> items, String rootCode) {
        Set<String> deactivatedParents = new HashSet<>();
        deactivatedParents.add(rootCode);
        int changed = 0;
        boolean found;
        do {
            found = false;
            for (CommonCodeItem item : List.copyOf(items.values())) {
                if (item.active() && item.parentCode() != null
                        && deactivatedParents.contains(item.parentCode())) {
                    items.put(item.code(), new CommonCodeItem(
                            item.code(), item.name(), item.parentCode(), item.sortOrder(), false,
                            item.validFrom(), item.validTo()));
                    deactivatedParents.add(item.code());
                    changed++;
                    found = true;
                }
            }
        } while (found);
        return changed;
    }

    /** 기본 적용 기간이 설정된 초기 코드 항목을 만든다. */
    private CommonCodeItem item(
            String code,
            String name,
            String parentCode,
            int sortOrder,
            boolean active) {
        return new CommonCodeItem(
                code, name, parentCode, sortOrder, active, LONG_AGO, FAR_FUTURE);
    }

    /** 필수 코드값을 대문자로 정규화하고 허용 문자·길이를 검사한다. */
    private String normalizeCode(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "는 필수입니다.");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    fieldName + "는 영문 대문자, 숫자, 밑줄만 사용할 수 있습니다.");
        }
        return normalized;
    }

    /** 선택 입력은 null을 유지하고 값이 있을 때만 필수 코드와 같은 규칙으로 정규화한다. */
    private String normalizeOptional(String value, String fieldName) {
        return value == null || value.isBlank() ? null : normalizeCode(value, fieldName);
    }

    /** 코드 표시명의 필수 여부와 최대 길이를 검사한다. */
    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("name은 1~100자의 값이어야 합니다.");
        }
    }

    /** 그룹 설명을 선택 문자열로 정규화하고 관리 화면 최대 길이를 제한한다. */
    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String normalized = description.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("description은 200자 이하여야 합니다.");
        }
        return normalized;
    }

    /** null 정렬 순서를 기본값으로 바꾸고 허용 범위를 검사한다. */
    private int validateSortOrder(Integer sortOrder) {
        int resolvedSortOrder = sortOrder == null ? 999 : sortOrder;
        if (resolvedSortOrder < 0 || resolvedSortOrder > 9999) {
            throw new IllegalArgumentException("sortOrder는 0~9999 범위여야 합니다.");
        }
        return resolvedSortOrder;
    }

    /** 적용 종료일이 시작일보다 앞서는 잘못된 기간을 차단한다. */
    private void validatePeriod(LocalDate validFrom, LocalDate validTo) {
        if (validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo는 validFrom보다 빠를 수 없습니다.");
        }
    }

    /**
     * 상위 코드의 존재 여부와 자기 참조·간접 순환 참조를 검사한다.
     * 기존 조상 연결을 따라가며 저장 대상 코드가 다시 나오면 순환 구조로 판단한다.
     */
    private void validateParent(
            Map<String, CommonCodeItem> items,
            String code,
            String parentCode) {
        if (parentCode == null) {
            return;
        }
        if (parentCode.equals(code)) {
            throw new IllegalArgumentException("자기 자신을 상위 코드로 지정할 수 없습니다.");
        }
        if (!items.containsKey(parentCode)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, parentCode);
        }
        String ancestorCode = parentCode;
        while (ancestorCode != null) {
            if (ancestorCode.equals(code)) {
                throw new IllegalArgumentException("공통코드 순환 참조가 발생할 수 없습니다.");
            }
            CommonCodeItem ancestor = items.get(ancestorCode);
            ancestorCode = ancestor == null ? null : ancestor.parentCode();
        }
    }

    /** 활성 조건과 상위 코드 조건까지 포함해 조회 캐시를 구분하는 키다. */
    private record QueryKey(String groupCode, boolean activeOnly, String parentCode) {
    }

    /** 코드 그룹의 표시·정렬·활성 상태와 시스템 보호 여부다. */
    public record CommonCodeGroup(
            String groupCode,
            String name,
            String description,
            int sortOrder,
            boolean active,
            boolean system) {
    }

    /** 그룹 식별자, 변경 버전과 조회된 코드 목록을 함께 제공하는 응답이다. */
    public record CodeGroupView(
            String groupCode,
            long version,
            List<CommonCodeItem> items) {
    }

    /** 일괄 저장의 단일 버전과 실제 변경·연쇄 비활성화 건수를 반환한다. */
    public record BulkSaveResult(
            String groupCode,
            long version,
            int changedCount,
            int cascadedCount,
            List<CommonCodeItem> items) {

        public BulkSaveResult {
            items = List.copyOf(items);
        }
    }

    /**
     * 코드 한 건의 표시명, 계층, 정렬, 활성 상태와 적용 기간이다.
     * active가 true여도 기준일이 적용 기간 밖이면 신규 입력 선택지에서는 제외된다.
     */
    public record CommonCodeItem(
            String code,
            String name,
            String parentCode,
            int sortOrder,
            boolean active,
            LocalDate validFrom,
            LocalDate validTo) {

        /** 계층·기간을 생략한 단순 코드 생성 시 기본 적용 기간을 사용한다. */
        public CommonCodeItem(String code, String name, int sortOrder, boolean active) {
            this(code, name, null, sortOrder, active, LONG_AGO, FAR_FUTURE);
        }

        /** 기준일에 활성 상태이고 적용 기간 안에 포함되는지 확인한다. */
        public boolean isSelectableOn(LocalDate date) {
            return active && !date.isBefore(validFrom) && !date.isAfter(validTo);
        }
    }
}
