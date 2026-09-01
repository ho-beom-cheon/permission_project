package com.example.permissiondemo.common;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final ConcurrentMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private final ConcurrentMap<QueryKey, List<CommonCodeItem>> viewCache =
            new ConcurrentHashMap<>();
    private final AuditEventService auditEventService;
    private final Clock clock;

    /** 단위 테스트에서 Spring 컨텍스트 없이 사용할 수 있도록 기본 감사 서비스와 UTC Clock을 구성한다. */
    public CommonCodeService() {
        this(new AuditEventService(), Clock.systemUTC());
    }

    /**
     * 실제 애플리케이션에서 공통 감사 서비스와 서울 시간대 Clock을 주입받고 데모 코드를 등록한다.
     * REGION 그룹은 상위 시·도와 하위 시·군·구 관계를 시험하기 위한 계층 예시다.
     */
    @Autowired
    public CommonCodeService(AuditEventService auditEventService, Clock clock) {
        this.auditEventService = auditEventService;
        this.clock = clock;
        register("USE_YN", item("Y", "사용", null, 10, true));
        register("USE_YN", item("N", "미사용", null, 20, true));
        register("ARTICLE_STATUS", item("DRAFT", "작성 중", null, 10, true));
        register("ARTICLE_STATUS", item("PUBLISHED", "게시", null, 20, true));
        register("ARTICLE_STATUS", item("DELETED", "삭제", null, 30, false));
        register("AUTH_STATUS", item("PENDING", "승인 대기", null, 10, true));
        register("AUTH_STATUS", item("APPROVED", "승인", null, 20, true));
        register("AUTH_STATUS", item("REVOKED", "회수", null, 30, true));
        register("REGION", item("SEOUL", "서울", null, 10, true));
        register("REGION", item("BUSAN", "부산", null, 20, true));
        register("REGION", item("GANGNAM", "강남구", "SEOUL", 10, true));
        register("REGION", item("MAPO", "마포구", "SEOUL", 20, true));
        register("REGION", item("HAEUNDAE", "해운대구", "BUSAN", 10, true));
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
        findGroup(normalizedGroup);
        String normalizedParent = normalizeOptional(parentCode, "parentCode");
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
    public CommonCodeItem saveItem(
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
    public CommonCodeItem saveItem(
            String groupCode,
            String code,
            String name,
            String parentCode,
            Integer sortOrder,
            Boolean active,
            LocalDate validFrom,
            LocalDate validTo) {
        String normalizedGroup = normalizeCode(groupCode, "groupCode");
        ConcurrentMap<String, CommonCodeItem> items = findGroup(normalizedGroup);
        String normalizedCode = normalizeCode(code, "code");
        String normalizedParent = normalizeOptional(parentCode, "parentCode");
        validateName(name);
        int resolvedSortOrder = validateSortOrder(sortOrder);
        LocalDate resolvedFrom = validFrom == null ? LONG_AGO : validFrom;
        LocalDate resolvedTo = validTo == null ? FAR_FUTURE : validTo;
        validatePeriod(resolvedFrom, resolvedTo);
        validateParent(items, normalizedCode, normalizedParent);

        // 모든 검증이 끝난 뒤 저장해 실패 요청이 기존 값을 일부 변경하지 않게 한다.
        CommonCodeItem saved = new CommonCodeItem(
                normalizedCode,
                name.trim(),
                normalizedParent,
                resolvedSortOrder,
                active == null || active,
                resolvedFrom,
                resolvedTo);
        CommonCodeItem previous = items.put(normalizedCode, saved);
        long changedVersion = versions.computeIfAbsent(
                normalizedGroup, ignored -> new AtomicLong()).incrementAndGet();
        // 같은 그룹을 조회한 모든 활성·상위 코드 조합을 제거해 다음 요청에서 최신 목록을 계산한다.
        viewCache.keySet().removeIf(key -> key.groupCode().equals(normalizedGroup));
        auditEventService.record(
                "COMMON_CODE_SAVED",
                "COMMON_CODE",
                normalizedGroup + ":" + normalizedCode,
                "SUCCESS",
                Map.of(
                        "changeType", previous == null ? "CREATED" : "UPDATED",
                        "groupVersion", changedVersion));
        return saved;
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

    /** 지정 그룹의 현재 버전을 반환한다. 그룹 존재 여부도 함께 검증한다. */
    private long version(String groupCode) {
        findGroup(groupCode);
        return versions.getOrDefault(groupCode, new AtomicLong()).get();
    }

    /** 초기 데모 코드를 등록하면서 해당 그룹 버전을 증가시킨다. */
    private void register(String groupCode, CommonCodeItem item) {
        groups.computeIfAbsent(groupCode, ignored -> new ConcurrentHashMap<>())
                .put(item.code(), item);
        versions.computeIfAbsent(groupCode, ignored -> new AtomicLong()).incrementAndGet();
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

    /** 그룹 식별자, 변경 버전과 조회된 코드 목록을 함께 제공하는 응답이다. */
    public record CodeGroupView(
            String groupCode,
            long version,
            List<CommonCodeItem> items) {
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
