package com.ramiart.admin.audit.application;

import static com.ramiart.admin.audit.application.AuditModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.audit.application.AuditRepository.AuditFilter;
import com.ramiart.admin.audit.application.AuditRepository.AuditRow;
import com.ramiart.admin.inquiry.application.InquiryDataProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private static final Map<String, Action> ACTIONS = actions();
    private static final Map<String, String> TASKS = Map.ofEntries(
            Map.entry("MGT-ADMIN-ACCOUNT", "관리자 계정"), Map.entry("MGT-AUTH-LOGIN", "관리자 로그인"),
            Map.entry("MGT-AUTH-REAUTHENTICATE", "민감 작업 재인증"), Map.entry("MGT-AUDIT-LIST", "감사 로그"),
            Map.entry("MGT-DATA-TRANSFER", "데이터 이관"), Map.entry("MGT-ATTENDANCE-TODAY", "출석 관리"),
            Map.entry("MGT-ATTENDANCE-CLOSE", "출석 마감"), Map.entry("MGT-STUDENT-CREATE", "원생 등록"),
            Map.entry("MGT-STUDENT-EDIT", "원생 정보 수정"), Map.entry("MGT-STUDENT-STATUS", "원생 상태 변경"),
            Map.entry("MGT-TUITION-PAYMENT-RECORD", "수업료 납입"), Map.entry("MGT-FINANCE-LEDGER", "재무 원장"));
    private static final List<Option> ACTOR_TYPES = List.of(new Option("ADMIN", "관리자"),
            new Option("SYSTEM", "시스템"), new Option("ANONYMOUS", "익명"));
    private static final List<Option> RESULTS = List.of(new Option("SUCCESS", "성공"), new Option("FAILURE", "실패"));
    private final AuditRepository repository;
    private final InquiryDataProtector protector;
    private final ObjectMapper mapper;

    public AuditService(AuditRepository repository, InquiryDataProtector protector, ObjectMapper mapper) {
        this.repository = repository; this.protector = protector; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Options options(Authentication auth) {
        require(auth);
        List<TaskOption> tasks = TASKS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new TaskOption(entry.getKey(), entry.getValue())).toList();
        List<ActionOption> actions = ACTIONS.values().stream().map(action -> new ActionOption(action.code(), action.label(),
                List.of(action.task()), action.eventClass(), action.targetType(), action.route(), action.targetPermission())).toList();
        return new Options(ACTOR_TYPES, RESULTS, tasks, actions);
    }

    @Transactional(readOnly = true)
    public ListResponse list(Query query, Authentication auth) {
        require(auth);
        Normalized normalized = normalize(query);
        Cursor cursor = decodeCursor(normalized.query().cursor(), normalized.filterHash());
        List<AuditRow> rows = repository.find(normalized.filter(), cursor == null ? null : cursor.at(),
                cursor == null ? null : cursor.id(), normalized.query().size() + 1);
        boolean more = rows.size() > normalized.query().size();
        List<AuditRow> pageRows = more ? rows.subList(0, normalized.query().size()) : rows;
        List<Item> items = pageRows.stream().map(this::item).toList();
        String next = more ? encodeCursor(pageRows.getLast(), normalized.filterHash()) : null;
        return new ListResponse(items, new PageInfo(normalized.query().size(), next));
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id, Authentication auth) {
        require(auth);
        AuditRow row = repository.findById(id).orElseThrow(() -> new AuditException("AUDIT_LOG_NOT_FOUND"));
        Action action = ACTIONS.get(row.action());
        Actor actor = new Actor(row.actorType(), row.actorId(), row.actorDisplay());
        TargetDetail target = row.targetType() == null ? null
                : new TargetDetail(row.targetType(), row.targetId(), row.targetDisplay());
        String link = targetLink(row, action, auth);
        return new Detail(row.id(), row.occurredAt(), row.requestId(), row.taskId(), actor, row.action(),
                action == null ? "알 수 없는 작업" : action.label(), target, row.result(), row.reasonCode(),
                maskIp(row.ipAddress()), safeUserAgent(row.userAgent()), safeDetails(row, action), link);
    }

    private Item item(AuditRow row) {
        Action action = ACTIONS.get(row.action());
        Target target = row.targetType() == null ? null : new Target(row.targetType(), row.targetDisplay());
        return new Item(row.id(), row.occurredAt(), new Actor(row.actorType(), row.actorId(), row.actorDisplay()),
                row.taskId(), row.action(), action == null ? "알 수 없는 작업" : action.label(), target, row.result());
    }

    private Normalized normalize(Query input) {
        if (input == null) throw new AuditException("AUDIT_FILTER_INVALID");
        LocalDate today = LocalDate.now(STUDIO_ZONE);
        LocalDate from = parseDate(input.from(), today.minusDays(6));
        LocalDate to = parseDate(input.to(), today);
        if (from.isAfter(to) || from.plusDays(89).isBefore(to)) throw new AuditException("AUDIT_PERIOD_INVALID");
        String actorType = blank(input.actorType());
        String taskId = blank(input.taskId());
        String action = blank(input.action());
        String result = blank(input.result());
        if (actorType != null && !Set.of("ADMIN", "SYSTEM", "ANONYMOUS").contains(actorType)
                || result != null && !Set.of("SUCCESS", "FAILURE").contains(result)
                || taskId != null && (taskId.length() > 100 || !taskId.matches("MGT-[A-Z0-9-]+"))
                || action != null && (action.length() > 100 || !action.matches("[A-Z][A-Z0-9_]*"))
                || input.actorId() != null && !"ADMIN".equals(actorType)
                || input.cursor() != null && input.cursor().length() > 500
                || input.size() < 20 || input.size() > 100)
            throw new AuditException("AUDIT_FILTER_INVALID");
        OffsetDateTime start = from.atStartOfDay(STUDIO_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(STUDIO_ZONE).toOffsetDateTime();
        String canonical = start + "|" + end + "|" + actorType + "|" + input.actorId() + "|" + taskId + "|" + action + "|" + result;
        String filterHash = protector.hash(canonical);
        Query normalized = new Query(from.toString(), to.toString(), actorType, input.actorId(), taskId, action, result,
                input.cursor(), input.size());
        return new Normalized(normalized, new AuditFilter(start, end, actorType, input.actorId(), taskId, action, result), filterHash);
    }

    private Cursor decodeCursor(String token, String filterHash) {
        if (token == null || token.isBlank()) return null;
        try {
            String[] parts = token.split("\\.", -1);
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (parts.length != 2 || !MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),
                    protector.hash(payload).getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            String[] values = payload.split("\\|", -1);
            if (values.length != 4 || !values[0].equals(filterHash) || Long.parseLong(values[3]) < System.currentTimeMillis() / 1000)
                throw new IllegalArgumentException();
            return new Cursor(OffsetDateTime.parse(values[1]), UUID.fromString(values[2]));
        } catch (RuntimeException exception) { throw new AuditException("AUDIT_CURSOR_INVALID"); }
    }

    private String encodeCursor(AuditRow row, String filterHash) {
        String payload = filterHash + "|" + row.occurredAt() + "|" + row.id() + "|" + (System.currentTimeMillis() / 1000 + 86400);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + protector.hash(payload);
    }

    private List<AllowedDetail> safeDetails(AuditRow row, Action action) {
        if (action == null || action.allowedDetails().isEmpty()) return List.of();
        try {
            Map<String, Object> details = mapper.readValue(row.detailsJson(), new TypeReference<>() {});
            List<AllowedDetail> safe = new ArrayList<>();
            for (String key : action.allowedDetails()) if (details.containsKey(key)) {
                Object value = details.get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean)
                    safe.add(new AllowedDetail(key, labelDetail(key), null, value));
            }
            return List.copyOf(safe);
        } catch (Exception exception) { return List.of(); }
    }

    private static String targetLink(AuditRow row, Action action, Authentication auth) {
        if (action == null || action.route() == null || row.targetId() == null || !has(auth, action.targetPermission())) return null;
        return action.route().replace("{targetId}", row.targetId().toString());
    }

    private static String labelDetail(String key) {
        return switch (key) { case "amount" -> "금액"; case "method" -> "납입 수단"; case "fromStatus" -> "변경 전 상태";
            case "toStatus" -> "변경 후 상태"; case "previousVersion" -> "이전 버전"; case "guardianCount" -> "보호자 수";
            case "reasonLength" -> "사유 길이"; case "confirmedCount" -> "확정 수"; case "failedCount" -> "실패 수";
            case "recordCount" -> "내보내기 건수"; default -> key; };
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value); } catch (RuntimeException exception) { throw new AuditException("AUDIT_FILTER_INVALID"); }
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String maskIp(String ip) {
        if (ip == null) return null;
        if (ip.indexOf(':') >= 0) { int end = ip.lastIndexOf(':'); return end < 0 ? "***" : ip.substring(0, end) + ":****"; }
        int dot = ip.lastIndexOf('.'); return dot < 0 ? "***" : ip.substring(0, dot) + ".xxx";
    }
    private static String safeUserAgent(String userAgent) {
        if (userAgent == null) return null;
        String value = userAgent.replaceAll("[\\p{Cntrl}]", "");
        return value.substring(0, Math.min(512, value.length()));
    }
    private static UUID require(Authentication auth) {
        if (!has(auth, "AUDIT_READ")) throw new AuditException("AUDIT_READ_DENIED");
        try { return UUID.fromString(auth.getName()); } catch (RuntimeException exception) { throw new AuditException("AUDIT_READ_DENIED"); }
    }
    private static boolean has(Authentication auth, String permission) {
        return permission != null && auth != null && auth.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private static Map<String, Action> actions() {
        Map<String, Action> values = new LinkedHashMap<>();
        register(values, "ADMIN_REAUTHENTICATED", "민감 작업 재인증", "MGT-AUTH-REAUTHENTICATE", "SECURITY", "ADMIN_SESSION", null, null);
        register(values, "STUDENT_CREATED", "원생 등록", "MGT-STUDENT-CREATE", "OPERATION", "STUDENT", "/admin/students/{targetId}", "STUDENT_READ", "guardianCount");
        register(values, "STUDENT_UPDATED", "원생 정보 수정", "MGT-STUDENT-EDIT", "OPERATION", "STUDENT", "/admin/students/{targetId}", "STUDENT_READ", "guardianCount", "previousVersion");
        register(values, "STUDENT_STATUS_CHANGED", "원생 상태 변경", "MGT-STUDENT-STATUS", "PRIVILEGE", "STUDENT", "/admin/students/{targetId}", "STUDENT_READ", "fromStatus", "toStatus", "reasonLength", "previousVersion");
        register(values, "TUITION_PAYMENT_CREATED", "수업료 납입 등록", "MGT-TUITION-PAYMENT-RECORD", "FINANCE", "TUITION_PAYMENT", null, null, "amount", "method");
        register(values, "DATA_TRANSFER_EXPORT_CREATED", "데이터 내보내기", "MGT-DATA-TRANSFER", "OPERATION", "DATA_TRANSFER_JOB", null, null, "domain", "preset", "recordCount");
        register(values, "DATA_TRANSFER_ROWS_CONFIRMED", "데이터 가져오기 확정", "MGT-DATA-TRANSFER", "OPERATION", "DATA_TRANSFER_JOB", null, null, "confirmedCount", "failedCount", "duplicateCount");
        return Map.copyOf(values);
    }
    private static void register(Map<String, Action> map, String code, String label, String task, String eventClass,
            String targetType, String route, String permission, String... details) {
        map.put(code, new Action(code, label, task, eventClass, targetType, route, permission, Set.of(details)));
    }
    private record Action(String code, String label, String task, String eventClass, String targetType,
            String route, String targetPermission, Set<String> allowedDetails) {}
    private record Cursor(OffsetDateTime at, UUID id) {}
    private record Normalized(Query query, AuditFilter filter, String filterHash) {}

    public static final class AuditException extends RuntimeException {
        private final String code;
        public AuditException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
