package com.example.permissiondemo.common;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 원본 기준정보의 입력 필드와 복합키를 정의하고 조회·편집·삭제·버전 검증을 공통화한다. */
@Service
@StateBoundary
public class ReferenceDataService implements StateParticipant {
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Map<String, Entry>> records = new TreeMap<>();
    private final CurrentUserContext current;
    private final AuditEventService audit;
    private final Clock clock;

    public ReferenceDataService(CurrentUserContext current, AuditEventService audit, Clock clock) {
        this.current = current; this.audit = audit; this.clock = clock;
        define("banks", "은행 코드", "bankCd", "bankCd|은행 코드|code|!", "bankNm|은행명|text|!", "oldBankNm|구 은행명|text", "bankRpCd|은행 대표코드|code", "insttSe|금융기관 구분|code", "cn|메모|textarea", "scrinDispFlag1|화면표시 1|flag", "scrinDispFlag2|화면표시 2|flag", "sortOrder|정렬 순서|number", "useFlag|사용 여부|flag");
        define("bank-branches", "은행 지점", "bankCd,brCd", "bankCd|은행 코드|code|!", "brCd|지점 코드|code|!", "brNm|지점명|text|!", "useFlag|사용 여부|flag");
        define("holidays", "공휴일", "day", "day|일자|date|!", "holidayNm|공휴일명|text|!", "holidayFlag|공휴일 구분|code|!");
        define("institutions", "연계기관", "insttSeq", "insttSeq|기관 번호|code|!", "insttSeCd|기관 구분|code|!", "insttNm|기관명|text|!", "chrger|담당자|text", "chrgDeptNm|담당 부서|text", "chrgerTel|담당자 전화|text", "linkCn|연계 내용|textarea", "linkSysNm|연계 시스템명|text");
        define("parameters", "업무 매개변수", "prmtSeCd,appStartDay", "prmtSeCd|매개변수 구분|code|!", "appStartDay|적용 시작일|date|!", "appEndDay|적용 종료일|date|!", "prmt|매개변수명|text|!", "prmtVal|매개변수 값|text|!", "operatorSe|연산자 구분|code", "rem|비고|textarea");
        define("code-values", "코드값", "cdSe,cd", "cdSe|코드 구분|code|!", "cd|코드|code|!", "cdNm|코드명|text|!", "cdVal|코드값|text", "sortOrder|정렬 순서|number", "useFlag|사용 여부|flag");
        define("message-templates", "기본 문자 메시지", "msgSeq", "msgSeq|메시지 번호|code|!", "jobSeCd|업무 구분|code|!", "jobSeDsc|업무 설명|text", "msgCn|메시지 내용|textarea|!", "msgForm|메시지 형식|code", "msgCycle|메시지 주기|code", "ntceMth|알림 방법|code", "useFlag|사용 여부|flag", "tmpltCd|템플릿 코드|code", "trnsMthCd|전송 방법|code");
        define("workdays", "월별 업무일정", "ym", "ym|기준 연월|month|!", "adjStartDay|조정 시작일|date", "adjEndDay|조정 종료일|date", "chenapCreatDay|체납 생성일|date", "gojiDataCreatDay|고지자료 생성일|date", "gojiPrintPosblDay|고지 출력가능일|date", "gojiDlvPrarnde|고지 송달예정일|date", "premmNopayCreatDay|전월 미납 생성일|date", "premmNopayDwd|전월 미납 인출일|date", "autopayNewlyClosDay|자동납부 신규 마감일|date", "autopayCrClosDay|자동납부 변경 마감일|date", "smDataCreatDay|수납자료 생성일|date", "realNapgi|실제 납기|date", "napgi|납기|date", "cgCalcWorkTm|요금 계산 작업시각|text", "premmNopayAutopayCreatDayD20|20일 자동납부 생성일|date", "premmNopayAutopayWthrDayD20|20일 자동납부 인출일|date", "premmNopayAutopayCreatDayD20Bbs|게시용 생성일|date", "premmNopayAutopayWithd2Bbs|게시용 재인출일|date", "egojiDlvDay|전자고지 송달일|date", "egojiDlvDayBbs|게시용 전자고지 송달일|date", "rem|비고|textarea");
    }

    private void define(String id, String name, String keys, String... specs) {
        List<Field> fields = Arrays.stream(specs).map(spec -> {
            String[] p = spec.split("\\|"); return new Field(p[0], p[1], p[2], p.length > 3);
        }).toList();
        definitions.put(id, new Definition(id, name, List.of(keys.split(",")), fields));
        records.put(id, new TreeMap<>());
    }
    public List<Definition> definitions() { return List.copyOf(definitions.values()); }
    public PageResult<Entry> list(String module, String query, PageQuery page) {
        definition(module);
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return PageResult.of(records.get(module).values().stream()
                .filter(item -> search.isEmpty() || item.values().values().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(search))).toList(), page);
    }

    public Entry save(String module, Map<String, String> values, long expectedVersion) {
        Definition definition = definition(module);
        if (values == null || values.size() > definition.fields().size()) throw new IllegalArgumentException("입력 필드를 확인해 주세요.");
        Map<String, String> clean = new LinkedHashMap<>();
        for (Field field : definition.fields()) {
            String value = Optional.ofNullable(values.get(field.id())).orElse("").trim();
            if (field.required() && value.isBlank()) throw new IllegalArgumentException(field.label() + " 항목은 필수입니다.");
            if (value.length() > (field.type().equals("textarea") ? 4000 : 250)) throw new IllegalArgumentException(field.label() + " 값이 너무 깁니다.");
            if (!value.isBlank()) validateField(field, value);
            clean.put(field.id(), value);
        }
        if (!clean.keySet().containsAll(values.keySet())) throw new IllegalArgumentException("허용되지 않은 입력 항목입니다.");
        String id = definition.keyFields().stream().map(clean::get).collect(java.util.stream.Collectors.joining("|"));
        Entry existing = records.get(module).get(id);
        if ((existing == null ? 0 : existing.version()) != expectedVersion) throw new ApiException(ErrorCode.CONFLICT, "다른 사용자가 변경했습니다. 다시 조회해 주세요.");
        checkPeriod(clean, "appStartDay", "appEndDay"); checkPeriod(clean, "adjStartDay", "adjEndDay");
        if (module.equals("bank-branches")) {
            Entry bank = records.get("banks").get(clean.get("bankCd"));
            if (bank == null || ("Y".equals(clean.get("useFlag")) && "N".equals(bank.values().get("useFlag")))) throw new ApiException(ErrorCode.CONFLICT, "사용 가능한 은행을 먼저 등록해 주세요.");
        }
        if (module.equals("parameters")) {
            LocalDate start = LocalDate.parse(clean.get("appStartDay")), end = LocalDate.parse(clean.get("appEndDay"));
            boolean overlaps = records.get(module).values().stream().filter(e -> !e.id().equals(id) && e.values().get("prmtSeCd").equals(clean.get("prmtSeCd")))
                    .anyMatch(e -> !LocalDate.parse(e.values().get("appEndDay")).isBefore(start) && !LocalDate.parse(e.values().get("appStartDay")).isAfter(end));
            if (overlaps) throw new ApiException(ErrorCode.CONFLICT, "같은 매개변수의 적용 기간이 겹칩니다.");
        }
        Instant now = Instant.now(clock); String actor = current.require().username();
        Entry saved = new Entry(id, Map.copyOf(clean), expectedVersion + 1, existing == null ? actor : existing.createdBy(), existing == null ? now : existing.createdAt(), actor, now);
        records.get(module).put(id, saved);
        if (module.equals("banks") && "N".equals(clean.get("useFlag"))) {
            records.get("bank-branches").replaceAll((key, branch) -> {
                if (!branch.values().get("bankCd").equals(id)) return branch;
                Map<String,String> updated = new LinkedHashMap<>(branch.values()); updated.put("useFlag", "N");
                return new Entry(branch.id(), Map.copyOf(updated), branch.version()+1, branch.createdBy(), branch.createdAt(), actor, now);
            });
        }
        audit.record("REFERENCE_SAVED", module, id, "SUCCESS", Map.of("version", saved.version())); return saved;
    }

    public void delete(String module, String id, long version) {
        definition(module); Entry entry = records.get(module).get(id);
        if (entry == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id);
        if (entry.version() != version) throw new ApiException(ErrorCode.CONFLICT, id);
        if (module.equals("banks") && records.get("bank-branches").values().stream().anyMatch(branch -> branch.values().get("bankCd").equals(id))) {
            throw new ApiException(ErrorCode.CONFLICT, "연결된 지점을 먼저 정리해 주세요.");
        }
        records.get(module).remove(id); audit.record("REFERENCE_DELETED", module, id, "SUCCESS", Map.of());
    }

    public String exportCsv(String module) {
        Definition definition = definition(module); StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(definition.fields().stream().map(f -> quote(f.label())).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for (Entry entry : records.get(module).values()) csv.append(definition.fields().stream().map(f -> quote(entry.values().get(f.id()))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        audit.record("REFERENCE_EXPORTED", module, module, "SUCCESS", Map.of("count", records.get(module).size()));
        return csv.toString();
    }
    private String quote(String value) { String safe = value == null ? "" : value; if (!safe.isEmpty() && "=+@-\t\r\n".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe; return "\"" + safe.replace("\"", "\"\"") + "\""; }
    private Definition definition(String id) { Definition value = definitions.get(id); if (value == null) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, id); return value; }
    private void checkPeriod(Map<String,String> values, String from, String to) {
        if (!values.getOrDefault(from, "").isBlank() && !values.getOrDefault(to, "").isBlank() && LocalDate.parse(values.get(to)).isBefore(LocalDate.parse(values.get(from)))) throw new IllegalArgumentException("종료일이 시작일보다 빠릅니다.");
    }
    private void validateField(Field field, String value) {
        try {
            switch (field.type()) {
                case "date" -> LocalDate.parse(value);
                case "month" -> YearMonth.parse(value);
                case "number" -> { if (new BigDecimal(value).signum() < 0) throw new IllegalArgumentException(); }
                case "flag" -> { if (!Set.of("Y", "N").contains(value)) throw new IllegalArgumentException(); }
                case "code" -> { if (!value.matches("[A-Za-z0-9_.-]{1,50}")) throw new IllegalArgumentException(); }
                default -> { }
            }
        } catch (RuntimeException exception) { throw new IllegalArgumentException(field.label() + " 형식을 확인해 주세요."); }
    }
    public record Field(String id, String label, String type, boolean required) { }
    public record Definition(String id, String name, List<String> keyFields, List<Field> fields) { }
    public record Entry(String id, Map<String,String> values, long version, String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) { }
    @Override public String stateKey() { return "reference-data"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(records); }
    @Override public void restoreState(Object raw) {
        records.clear(); definitions.keySet().forEach(key -> records.put(key, new TreeMap<>()));
        ((StoredState) raw).records().forEach((key, value) -> { definition(key); records.put(key, new TreeMap<>(value)); });
    }
    public record StoredState(Map<String, Map<String, Entry>> records) { }
}
