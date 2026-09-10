package com.example.permissiondemo.authorization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 원본 권한 신청의 시스템별 이용약관과 사용자 동의 이력을 보관한다. */
@Service
@StateBoundary
public class AuthorityTermsService implements StateParticipant {

    private static final List<String> SYSTEM_ORDER = List.of("AI", "CY", "MO", "AB");
    private static final Set<String> SYSTEMS = Set.copyOf(SYSTEM_ORDER);
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern TWO_ALPHANUMERIC = Pattern.compile("[A-Za-z0-9]{2}");
    private static final DateTimeFormatter SOURCE_DATE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Comparator<AuthorityTerm> TERM_ORDER = Comparator
            .comparing(AuthorityTerm::typeCode)
            .thenComparingInt(AuthorityTerm::sortOrder)
            .thenComparing(AuthorityTerm::required, Comparator.reverseOrder())
            .thenComparing(AuthorityTerm::version, Comparator.reverseOrder())
            .thenComparingLong(AuthorityTerm::seq);

    private final Map<Long, AuthorityTerm> terms = new LinkedHashMap<>();
    private final List<Agreement> agreements = new ArrayList<>();
    private final AuthorizationCatalog catalog;
    private final Clock clock;
    private long version;

    public AuthorityTermsService(AuthorizationCatalog catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    public synchronized List<AuthorityTerm> terms() {
        return terms.values().stream()
                .sorted(Comparator.comparingInt((AuthorityTerm item) -> SYSTEM_ORDER.indexOf(item.systemId()))
                        .thenComparing(TERM_ORDER))
                .toList();
    }

    public synchronized List<Agreement> agreements(String username) {
        return agreements.stream().filter(item -> item.username().equals(username)).toList();
    }

    public synchronized long version() {
        return version;
    }

    /** 모든 원본 행을 먼저 검증하고 실제 차이가 있는 약관만 한 번에 반영한다. */
    public synchronized ImportResult importTerms(ImportRequest request) {
        List<AuthorityTerm> validated = validateImport(request);
        List<Long> created = new ArrayList<>();
        List<Long> updated = new ArrayList<>();
        List<Long> unchanged = new ArrayList<>();
        for (AuthorityTerm term : validated) {
            AuthorityTerm previous = terms.get(term.seq());
            if (previous == null) created.add(term.seq());
            else if (previous.equals(term)) unchanged.add(term.seq());
            else updated.add(term.seq());
        }
        if (!created.isEmpty() || !updated.isEmpty()) {
            validated.forEach(term -> terms.put(term.seq(), term));
            version++;
        }
        return new ImportResult(List.copyOf(created), List.copyOf(updated), List.copyOf(unchanged), version);
    }

    /** 원본 append 이력을 전체 검증하고 아직 없는 원본 행만 추가한다. */
    public synchronized AgreementImportResult importAgreements(AgreementImportRequest request) {
        List<Agreement> validated = validateAgreementImport(request);
        Set<AgreementSourceIdentity> stored = agreements.stream()
                .filter(item -> item.duplicateOrdinal() > 0)
                .map(AgreementSourceIdentity::of)
                .collect(java.util.stream.Collectors.toSet());
        int created = 0;
        int unchanged = 0;
        for (Agreement agreement : validated) {
            if (stored.add(AgreementSourceIdentity.of(agreement))) {
                agreements.add(agreement);
                created++;
            } else {
                unchanged++;
            }
        }
        if (created > 0) version++;
        return new AgreementImportResult(created, unchanged, version);
    }

    /** 선택한 활성 권한의 원본 시스템 순서에 따라 아직 동의가 필요한 시스템 약관을 반환한다. */
    public synchronized TermsPlan plan(String username, Set<String> authorityIds) {
        if (username == null || username.isBlank() || authorityIds == null || authorityIds.isEmpty()) {
            throw new IllegalArgumentException("사용자와 신청 권한이 필요합니다.");
        }
        LinkedHashSet<String> selectedSystems = new LinkedHashSet<>();
        Set<String> authoritySystems = new HashSet<>();
        for (String authorityId : authorityIds) {
            AuthorizationCatalog.AuthorityDefinition authority = catalog.findAuthority(authorityId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, authorityId));
            if (!authority.active()) throw new ApiException(ErrorCode.CONFLICT, authorityId);
            if (SYSTEMS.contains(authority.systemId())) authoritySystems.add(authority.systemId());
        }
        SYSTEM_ORDER.stream().filter(authoritySystems::contains).forEach(selectedSystems::add);

        List<AuthorityTerm> requiredTerms = new ArrayList<>();
        LinkedHashSet<String> unconfiguredSystems = new LinkedHashSet<>();
        for (String systemId : selectedSystems) {
            List<AuthorityTerm> current = terms.values().stream()
                    .filter(item -> item.systemId().equals(systemId) && item.typeCode().equals("01")
                            && item.statusCode().equals("02") && item.active())
                    .sorted(TERM_ORDER).toList();
            List<AuthorityTerm> mandatory = current.stream().filter(AuthorityTerm::required).toList();
            if (mandatory.isEmpty()) {
                unconfiguredSystems.add(systemId);
            } else if (mandatory.stream().anyMatch(item -> !hasPositiveAgreement(username, item.seq()))) {
                requiredTerms.addAll(current);
            }
        }
        return new TermsPlan(List.copyOf(selectedSystems), List.copyOf(requiredTerms),
                Collections.unmodifiableSet(new LinkedHashSet<>(unconfiguredSystems)));
    }

    /** 현재 계획 전체에 대한 결정을 검증한다. 이전 Y 이력은 필수 동의 충족으로 재사용한다. */
    public synchronized TermsPlan validateDecisions(
            String username,
            Set<String> authorityIds,
            List<AgreementDecision> decisions) {
        TermsPlan current = plan(username, authorityIds);
        if (decisions == null || decisions.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("약관별 동의 여부가 필요합니다.");
        }
        Map<Long, AgreementDecision> bySeq = new LinkedHashMap<>();
        for (AgreementDecision decision : decisions) {
            if (decision.seq() <= 0 || bySeq.putIfAbsent(decision.seq(), decision) != null) {
                throw new IllegalArgumentException("약관 결정을 중복 없이 입력해 주세요.");
            }
        }
        Set<Long> planned = current.terms().stream().map(AuthorityTerm::seq)
                .collect(java.util.stream.Collectors.toSet());
        if (!planned.equals(bySeq.keySet())) {
            throw new ApiException(ErrorCode.CONFLICT, "현재 신청 약관을 다시 확인해 주세요.");
        }
        for (AuthorityTerm term : current.terms()) {
            if (term.required() && !bySeq.get(term.seq()).agreed()
                    && !hasPositiveAgreement(username, term.seq())) {
                throw new IllegalArgumentException("필수 약관에 동의해 주세요.");
            }
        }
        return current;
    }

    /** 검증된 현재 계획의 모든 결정을 동의 이력으로 추가한다. */
    public synchronized List<Agreement> recordDecisions(
            String username,
            Set<String> authorityIds,
            List<AgreementDecision> decisions) {
        TermsPlan current = validateDecisions(username, authorityIds, decisions);
        Map<Long, AgreementDecision> bySeq = new LinkedHashMap<>();
        decisions.forEach(item -> bySeq.put(item.seq(), item));
        Instant agreedAt = Instant.now(clock);
        List<Agreement> recorded = current.terms().stream()
                .map(term -> new Agreement(username, term.seq(), bySeq.get(term.seq()).agreed(), agreedAt))
                .toList();
        agreements.addAll(recorded);
        return recorded;
    }

    private List<AuthorityTerm> validateImport(ImportRequest request) {
        if (request == null || request.terms() == null || request.terms().isEmpty()) {
            throw new IllegalArgumentException("이관할 이용약관 원본 행이 필요합니다.");
        }
        Set<Long> seen = new HashSet<>();
        List<AuthorityTerm> validated = new ArrayList<>();
        for (TermRow row : request.terms()) {
            if (row == null) throw new IllegalArgumentException("이용약관 원본 행은 null일 수 없습니다.");
            if (row.seq() <= 0 || !seen.add(row.seq())) {
                throw new IllegalArgumentException("이용약관 순번은 양수이며 중복될 수 없습니다.");
            }
            if (!SYSTEMS.contains(row.systemId())) {
                throw new IllegalArgumentException("시스템은 AI, CY, MO, AB 중 하나여야 합니다.");
            }
            if (row.typeCode() == null || !TWO_ALPHANUMERIC.matcher(row.typeCode()).matches()
                    || row.statusCode() == null || !TWO_ALPHANUMERIC.matcher(row.statusCode()).matches()) {
                throw new IllegalArgumentException("약관 유형과 상태 코드는 영문·숫자 2자리여야 합니다.");
            }
            text(row.version(), 50, "약관 버전");
            text(row.title(), 200, "약관 제목");
            text(row.body(), 100000, "약관 내용");
            LocalDate enforcementStart = parseDate(row.enforcementStart(), "약관 시행 시작일");
            LocalDate enforcementEnd = parseDate(row.enforcementEnd(), "약관 시행 종료일");
            if (enforcementStart != null && enforcementEnd != null
                    && enforcementEnd.isBefore(enforcementStart)) {
                throw new IllegalArgumentException("약관 시행 종료일이 시작일보다 빠릅니다.");
            }
            if (!"Y".equals(row.requiredFlag()) && !"N".equals(row.requiredFlag())
                    || !"Y".equals(row.useFlag()) && !"N".equals(row.useFlag())) {
                throw new IllegalArgumentException("필수동의와 사용 여부는 Y 또는 N이어야 합니다.");
            }
            if (row.sortOrder() == null || row.sortOrder() < 0 || row.sortOrder() > 9999) {
                throw new IllegalArgumentException("정렬순서는 0~9999여야 합니다.");
            }
            validated.add(new AuthorityTerm(row.seq(), row.systemId(), row.typeCode(), row.version(),
                    row.title(), row.body(), row.statusCode(), enforcementStart, enforcementEnd,
                    "Y".equals(row.requiredFlag()), row.sortOrder(), "Y".equals(row.useFlag())));
        }
        return List.copyOf(validated);
    }

    private List<Agreement> validateAgreementImport(AgreementImportRequest request) {
        if (request == null || request.agreements() == null || request.agreements().isEmpty()
                || request.agreements().size() > 10000) {
            throw new IllegalArgumentException("이관할 동의 이력은 1~10000건이어야 합니다.");
        }
        Set<AgreementSourceIdentity> seen = new HashSet<>();
        Map<AgreementSourceGroup, Set<Integer>> ordinalsByGroup = new LinkedHashMap<>();
        List<Agreement> validated = new ArrayList<>();
        for (AgreementRow row : request.agreements()) {
            if (row == null) throw new IllegalArgumentException("동의 이력 원본 행은 null일 수 없습니다.");
            if (row.username() == null || row.username().isBlank()) {
                throw new IllegalArgumentException("동의 이력 사용자 ID가 필요합니다.");
            }
            optionalText(row.username(), 200, "동의 이력 사용자 ID");
            optionalText(row.connectionIp(), 200, "접속 IP");
            optionalText(row.registrarId(), 200, "등록자 ID");
            if (row.termSequence() <= 0 || !terms.containsKey(row.termSequence())) {
                throw new IllegalArgumentException("동의 이력의 약관 순번이 이관된 약관을 참조해야 합니다.");
            }
            if (!"Y".equals(row.agreedFlag()) && !"N".equals(row.agreedFlag())) {
                throw new IllegalArgumentException("동의 여부는 Y 또는 N이어야 합니다.");
            }
            if (row.duplicateOrdinal() == null || row.duplicateOrdinal() <= 0) {
                throw new IllegalArgumentException("원본 중복 순번은 양수여야 합니다.");
            }
            Instant agreedAt = parseSourceDateTime(row.agreedAt(), "약관 동의일시", false);
            Instant registeredAt = parseSourceDateTime(row.registeredAt(), "등록일시", true);
            Agreement agreement = new Agreement(row.username(), row.termSequence(), "Y".equals(row.agreedFlag()),
                    agreedAt, row.connectionIp(), registeredAt, row.registrarId(), row.duplicateOrdinal());
            if (!seen.add(AgreementSourceIdentity.of(agreement))) {
                throw new IllegalArgumentException("동일한 원본 동의 이력을 중복 입력할 수 없습니다.");
            }
            ordinalsByGroup.computeIfAbsent(AgreementSourceGroup.of(agreement), ignored -> new HashSet<>())
                    .add(agreement.duplicateOrdinal());
            validated.add(agreement);
        }
        for (Set<Integer> ordinals : ordinalsByGroup.values()) {
            for (int expected = 1; expected <= ordinals.size(); expected++) {
                if (!ordinals.contains(expected)) {
                    throw new IllegalArgumentException("원본 중복 순번은 같은 원본 행별로 1부터 연속되어야 합니다.");
                }
            }
        }
        return List.copyOf(validated);
    }

    private void optionalText(String value, int maximum, String fieldName) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + "의 길이를 확인해 주세요.");
        }
    }

    private void text(String value, int maximum, String fieldName) {
        if (value == null || value.length() > maximum) {
            throw new IllegalArgumentException(fieldName + "의 길이를 확인해 주세요.");
        }
    }

    /** 원본 8자리 일자와 API 표준 ISO 일자만 허용하고 내부에서는 LocalDate로 통일한다. */
    private LocalDate parseDate(String value, String fieldName) {
        if (value == null) return null;
        try {
            if (value.matches("[0-9]{8}")) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            }
        } catch (DateTimeParseException ignored) {
            // 아래의 단일 업무 오류로 변환한다.
        }
        throw new IllegalArgumentException(fieldName + "은 YYYYMMDD 또는 YYYY-MM-DD 형식이어야 합니다.");
    }

    private Instant parseSourceDateTime(String value, String fieldName, boolean nullable) {
        if (value == null && nullable) return null;
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")) {
            throw new IllegalArgumentException(fieldName + "은 YYYY-MM-DDTHH:mm:ss 형식이어야 합니다.");
        }
        try {
            return LocalDateTime.parse(value, SOURCE_DATE_TIME)
                    .atZone(SOURCE_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(fieldName + "은 유효한 YYYY-MM-DDTHH:mm:ss 값이어야 합니다.");
        }
    }

    private boolean hasPositiveAgreement(String username, long seq) {
        return agreements.stream().anyMatch(item -> item.username().equals(username)
                && item.seq() == seq && item.agreed());
    }

    public record AuthorityTerm(
            long seq,
            String systemId,
            String typeCode,
            String version,
            String title,
            String body,
            String statusCode,
            LocalDate enforcementStart,
            LocalDate enforcementEnd,
            boolean required,
            int sortOrder,
            boolean active) { }

    public record Agreement(
            String username,
            long seq,
            boolean agreed,
            Instant agreedAt,
            String connectionIp,
            Instant registeredAt,
            String registrarId,
            int duplicateOrdinal) {
        /** 기존 실행 이력과 저장 JSON은 원본 이관 메타데이터 없이 그대로 호환한다. */
        public Agreement(String username, long seq, boolean agreed, Instant agreedAt) {
            this(username, seq, agreed, agreedAt, null, null, null, 0);
        }
    }

    public record TermRow(
            long seq,
            String systemId,
            String typeCode,
            String version,
            String title,
            String body,
            String statusCode,
            String enforcementStart,
            String enforcementEnd,
            String requiredFlag,
            Integer sortOrder,
            String useFlag) { }

    public record ImportRequest(List<TermRow> terms) { }

    public record ImportResult(List<Long> created, List<Long> updated, List<Long> unchanged, long version) { }

    public record AgreementRow(
            String username,
            long termSequence,
            String agreedAt,
            String agreedFlag,
            String connectionIp,
            String registeredAt,
            String registrarId,
            Integer duplicateOrdinal) { }

    public record AgreementImportRequest(List<AgreementRow> agreements) { }

    public record AgreementImportResult(int created, int unchanged, long version) { }

    public record TermsPlan(List<String> systemIds, List<AuthorityTerm> terms, Set<String> unconfiguredSystems) { }

    public record AgreementDecision(long seq, boolean agreed) { }

    private record AgreementSourceIdentity(
            String username,
            long termSequence,
            boolean agreed,
            Instant agreedAt,
            String connectionIp,
            Instant registeredAt,
            String registrarId,
            int duplicateOrdinal) {
        private static AgreementSourceIdentity of(Agreement agreement) {
            return new AgreementSourceIdentity(agreement.username(), agreement.seq(), agreement.agreed(),
                    agreement.agreedAt(), agreement.connectionIp(), agreement.registeredAt(),
                    agreement.registrarId(), agreement.duplicateOrdinal());
        }
    }

    private record AgreementSourceGroup(
            String username,
            long termSequence,
            boolean agreed,
            Instant agreedAt,
            String connectionIp,
            Instant registeredAt,
            String registrarId) {
        private static AgreementSourceGroup of(Agreement agreement) {
            return new AgreementSourceGroup(agreement.username(), agreement.seq(), agreement.agreed(),
                    agreement.agreedAt(), agreement.connectionIp(), agreement.registeredAt(),
                    agreement.registrarId());
        }
    }

    public record StoredState(List<AuthorityTerm> terms, List<Agreement> agreements, long version) { }

    @Override public String stateKey() { return "authority-terms"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public synchronized Object snapshotState() {
        return new StoredState(terms(), List.copyOf(agreements), version);
    }
    @Override public synchronized void restoreState(Object raw) {
        terms.clear();
        agreements.clear();
        version = 0;
        if (raw == null) return;
        StoredState state = (StoredState) raw;
        if (state.terms() != null) state.terms().forEach(item -> terms.put(item.seq(), item));
        if (state.agreements() != null) agreements.addAll(state.agreements());
        version = state.version();
    }
}
