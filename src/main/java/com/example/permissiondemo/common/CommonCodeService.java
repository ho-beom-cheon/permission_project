package com.example.permissiondemo.common;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 공통코드 그룹·상세·하위를 관리한다. 하위 코드는 상위 상세코드와 합쳐 식별한다. */
@Service
@StateBoundary
public class CommonCodeService implements StateParticipant {
    private static final LocalDate LONG_AGO = LocalDate.of(2000, 1, 1), FAR_FUTURE = LocalDate.of(2099, 12, 31);
    private final Map<String, Map<String, CommonCodeItem>> groups = new TreeMap<>();
    private final Map<String, CodeGroup> definitions = new TreeMap<>();
    private final Map<String, Long> versions = new TreeMap<>();
    private final AuditEventService audit;
    private final Clock clock;

    public CommonCodeService() { this(new AuditEventService(), Clock.systemUTC()); }
    @Autowired public CommonCodeService(AuditEventService audit, Clock clock) {
        this.audit = audit; this.clock = clock;
        register("USE_YN", "사용 여부", new CommonCodeItem("Y", "사용", 10, true), new CommonCodeItem("N", "미사용", 20, true));
        register("ARTICLE_STATUS", "게시 상태", new CommonCodeItem("DRAFT", "작성 중", 10, true), new CommonCodeItem("PUBLISHED", "게시", 20, true), new CommonCodeItem("DELETED", "삭제", 30, false));
        register("AUTH_STATUS", "권한 상태", new CommonCodeItem("PENDING", "승인 대기", 10, true), new CommonCodeItem("APPROVED", "승인", 20, true), new CommonCodeItem("REVOKED", "회수", 30, true));
        register("REGION", "지역", new CommonCodeItem("SEOUL", "서울", 10, true), new CommonCodeItem("BUSAN", "부산", 20, true),
                new CommonCodeItem("GANGNAM", "강남구", "SEOUL", 10, true, LONG_AGO, FAR_FUTURE),
                new CommonCodeItem("MAPO", "마포구", "SEOUL", 20, true, LONG_AGO, FAR_FUTURE),
                new CommonCodeItem("HAEUNDAE", "해운대구", "BUSAN", 10, true, LONG_AGO, FAR_FUTURE));
    }
    private void register(String id, String name, CommonCodeItem... items) {
        definitions.put(id, new CodeGroup(id, name, "가상 초기 코드", true));
        Map<String, CommonCodeItem> values = new TreeMap<>();
        for (CommonCodeItem item : items) values.put(key(item.code(), item.parentCode()), item);
        groups.put(id, values); versions.put(id, (long) items.length);
    }
    public List<CommonCodeItem> findItems(String group) { return findItems(group, true, null); }
    public List<CommonCodeItem> findActiveItems(String group) { return findItems(group, true, null); }
    public List<CommonCodeItem> findItems(String group, boolean activeOnly, String parentCode) {
        String id = code(group); Map<String, CommonCodeItem> items = requireGroup(id);
        String parent = optionalCode(parentCode); LocalDate today = LocalDate.now(clock);
        return items.values().stream().filter(item -> parent == null || parent.equals(item.parentCode()))
                .filter(item -> !activeOnly || definitions.get(id).active() && item.isSelectableOn(today)
                        && (item.parentCode() == null || Optional.ofNullable(items.get(item.parentCode())).map(p -> p.isSelectableOn(today)).orElse(false)))
                .sorted(Comparator.comparingInt(CommonCodeItem::sortOrder).thenComparing(CommonCodeItem::code).thenComparing(i -> Objects.toString(i.parentCode(), ""))).toList();
    }
    public CodeGroupView findGroupView(String group, boolean activeOnly, String parentCode) {
        String id = code(group); return new CodeGroupView(id, version(id), findItems(id, activeOnly, parentCode));
    }
    public List<CodeGroup> definitions() { return List.copyOf(definitions.values()); }
    public CodeGroup saveGroup(CodeGroup value, long expectedVersion) {
        if(value == null) throw new IllegalArgumentException("그룹 정보가 필요합니다.");
        String id = code(value.code()); text(value.name(), 100, true); text(value.description(), 2000, false);
        if (versions.getOrDefault(id, 0L) != expectedVersion) throw new ApiException(ErrorCode.CONFLICT, id);
        CodeGroup saved = new CodeGroup(id, value.name().trim(), Objects.toString(value.description(), ""), value.active());
        groups.computeIfAbsent(id, ignored -> new TreeMap<>()); definitions.put(id, saved); changed(id, "COMMON_CODE_GROUP_SAVED"); return saved;
    }
    public void deleteGroup(String group, long expectedVersion) {
        String id = code(group); Map<String, CommonCodeItem> items = requireGroup(id); checkVersion(id, expectedVersion);
        if (!items.isEmpty()) throw new ApiException(ErrorCode.CONFLICT, "상세코드를 먼저 삭제해 주세요.");
        if (Set.of("USE_YN", "ARTICLE_STATUS", "AUTH_STATUS", "REGION").contains(id)) throw new ApiException(ErrorCode.CONFLICT, "기본 화면에서 사용하는 코드 그룹입니다.");
        definitions.remove(id); groups.remove(id); versions.remove(id); audit.record("COMMON_CODE_GROUP_DELETED", "COMMON_CODE", id, "SUCCESS", Map.of());
    }
    public CommonCodeItem saveItem(String group, String code, String name, Integer sortOrder, Boolean active) {
        return saveItem(group, code, name, null, sortOrder, active, null, null);
    }
    public CommonCodeItem saveItem(String group, String code, String name, String parentCode, Integer sortOrder, Boolean active, LocalDate from, LocalDate to) {
        String id = code(group), itemCode = code(code), parent = optionalCode(parentCode);
        CommonCodeItem previous = requireGroup(id).get(key(itemCode, parent));
        return saveRich(id, new CommonCodeItem(itemCode, name, parent, sortOrder == null ? 999 : sortOrder,
                active == null || active, from == null ? LONG_AGO : from, to == null ? FAR_FUTURE : to,
                previous == null ? DisplayDetails.empty() : previous.details()), version(id));
    }
    public CommonCodeItem saveRich(String group, CommonCodeItem value, long expectedVersion) {
        if(value == null) throw new IllegalArgumentException("코드 정보가 필요합니다.");
        String id = code(group), itemCode = code(value.code()), parent = optionalCode(value.parentCode());
        Map<String, CommonCodeItem> items = requireGroup(id); checkVersion(id, expectedVersion); text(value.name(), 100, true);
        LocalDate from = value.validFrom() == null ? LONG_AGO : value.validFrom(), to = value.validTo() == null ? FAR_FUTURE : value.validTo();
        if (to.isBefore(from) || value.sortOrder() < 0 || value.sortOrder() > 9999) throw new IllegalArgumentException("적용 기간 또는 정렬 순서를 확인해 주세요.");
        if (parent != null) {
            CommonCodeItem upper = items.get(parent);
            if (upper == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, parent);
            if (upper.parentCode() != null || parent.equals(itemCode)) throw new IllegalArgumentException("상위 상세코드를 확인해 주세요.");
            if (value.active() && !upper.active()) throw new ApiException(ErrorCode.CONFLICT, "상위 상세코드가 미사용 상태입니다.");
        }
        DisplayDetails details = value.details() == null ? DisplayDetails.empty() : value.details();
        text(details.description(), 2000, false); text(details.substituteName(), 100, false); text(details.screenDescription(), 2000, false);
        CommonCodeItem saved = new CommonCodeItem(itemCode, value.name().trim(), parent, value.sortOrder(), value.active(), from, to, details);
        items.put(key(itemCode, parent), saved);
        // 상세코드를 미사용으로 바꾸면 하위코드에도 전파한다.
        if (parent == null && !saved.active()) items.replaceAll((key, child) -> itemCode.equals(child.parentCode())
                ? new CommonCodeItem(child.code(), child.name(), child.parentCode(), child.sortOrder(), false, child.validFrom(), child.validTo(), child.details()) : child);
        changed(id, "COMMON_CODE_SAVED"); return saved;
    }
    public void deleteItem(String group, String code, String parentCode, long expectedVersion) {
        String id = code(group), itemCode = code(code), parent = optionalCode(parentCode); Map<String, CommonCodeItem> items = requireGroup(id);
        checkVersion(id, expectedVersion);
        if (!items.containsKey(key(itemCode, parent))) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, itemCode);
        if (parent == null && items.values().stream().anyMatch(i -> itemCode.equals(i.parentCode()))) throw new ApiException(ErrorCode.CONFLICT, "하위코드를 먼저 삭제해 주세요.");
        items.remove(key(itemCode, parent)); changed(id, "COMMON_CODE_DELETED");
    }
    public Map<String, Integer> groupSummary() { Map<String,Integer> result = new TreeMap<>(); groups.forEach((id, items) -> result.put(id, items.size())); return Map.copyOf(result); }
    public Map<String, Long> groupVersions() { return Map.copyOf(versions); }
    private long version(String id) { requireGroup(id); return versions.get(id); }
    private void checkVersion(String id, long expected) { if (version(id) != expected) throw new ApiException(ErrorCode.CONFLICT, "코드가 변경됐습니다. 다시 조회해 주세요."); }
    private void changed(String id, String event) { versions.merge(id, 1L, Long::sum); audit.record(event, "COMMON_CODE", id, "SUCCESS", Map.of("groupVersion", versions.get(id))); }
    private Map<String, CommonCodeItem> requireGroup(String id) { var result = groups.get(id); if (result == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id); return result; }
    private static String key(String code, String parent) { return parent == null ? code : parent + ":" + code; }
    private static String code(String value) { String id = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT); if (!id.matches("[A-Z0-9_]{1,30}")) throw new IllegalArgumentException("코드는 영문·숫자·밑줄 1~30자로 입력해 주세요."); return id; }
    private static String optionalCode(String value) { return value == null || value.isBlank() ? null : code(value); }
    private static void text(String value, int max, boolean required) { if (required && (value == null || value.isBlank()) || value != null && value.length() > max) throw new IllegalArgumentException("문자열의 필수 여부와 최대 길이를 확인해 주세요."); }
    public record CodeGroup(String code, String name, String description, boolean active) { }
    public record CodeGroupView(String groupCode, long version, List<CommonCodeItem> items) { }
    public record DisplayDetails(String description, String substituteName, String screenDescription, boolean screen1, boolean screen2, boolean screen3, boolean screen4, boolean screen5) {
        public static DisplayDetails empty() { return new DisplayDetails("", "", "", true, true, true, true, true); }
    }
    public record CommonCodeItem(String code, String name, String parentCode, int sortOrder, boolean active, LocalDate validFrom, LocalDate validTo, DisplayDetails details) {
        public CommonCodeItem { if (details == null) details = DisplayDetails.empty(); }
        public CommonCodeItem(String code, String name, String parentCode, int sortOrder, boolean active, LocalDate from, LocalDate to) { this(code, name, parentCode, sortOrder, active, from, to, DisplayDetails.empty()); }
        public CommonCodeItem(String code, String name, int sortOrder, boolean active) { this(code, name, null, sortOrder, active, LONG_AGO, FAR_FUTURE); }
        public boolean isSelectableOn(LocalDate date) { return active && !date.isBefore(validFrom) && !date.isAfter(validTo); }
    }
    @Override public String stateKey() { return "common-codes"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { Map<String,List<CommonCodeItem>> result = new TreeMap<>(); groups.forEach((id, items) -> result.put(id, List.copyOf(items.values()))); return new StoredState(result, groupVersions(), definitions()); }
    @Override public void restoreState(Object raw) {
        StoredState state = (StoredState) raw; groups.clear(); definitions.clear();
        state.groups().forEach((id, items) -> { Map<String,CommonCodeItem> map = new TreeMap<>(); items.forEach(item -> map.put(key(item.code(), item.parentCode()), item)); groups.put(id, map); definitions.put(id, new CodeGroup(id, id, "", true)); });
        if (state.definitions() != null) state.definitions().forEach(value -> definitions.put(value.code(), value));
        versions.clear(); versions.putAll(state.versions());
    }
    public record StoredState(Map<String,List<CommonCodeItem>> groups, Map<String,Long> versions, List<CodeGroup> definitions) { }
}
