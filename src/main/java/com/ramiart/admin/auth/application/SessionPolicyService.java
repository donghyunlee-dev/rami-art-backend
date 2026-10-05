package com.ramiart.admin.auth.application;

import static com.ramiart.admin.auth.application.SessionPolicyModels.*;
import com.ramiart.admin.inquiry.application.InquiryDataProtector;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionPolicyService {
    private final JdbcClient jdbc;
    private final InquiryDataProtector protector;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;

    public SessionPolicyService(JdbcClient jdbc, InquiryDataProtector protector, AuditRecorder audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.protector = protector;
        this.audit = audit;
        this.mapper = mapper;
    }

    public PolicyPage list(String cursor, int size, Authentication authentication) {
        require(authentication, "SECURITY_POLICY_READ");
        if (size < 10 || size > 50) throw new SessionPolicyException("VALIDATION_ERROR");
        Integer after = decode(cursor);
        Policy current = jdbc.sql(sql() + " where p.effective_to is null")
                .query((row, index) -> map(row)).optional()
                .orElseThrow(() -> new SessionPolicyException("ACTIVE_SESSION_POLICY_MISSING"));
        var statement = jdbc.sql(sql() + " where p.effective_to is not null" +
                (after == null ? "" : " and p.version < :after") + " order by p.version desc limit :limit");
        if (after != null) statement.param("after", after);
        List<Policy> rows = statement.param("limit", size + 1).query((row, index) -> map(row)).list();
        boolean more = rows.size() > size;
        List<Policy> page = more ? rows.subList(0, size) : rows;
        String next = more ? encode(page.getLast().version()) : null;
        return new PolicyPage(current, page, new Page(size, next));
    }

    @Transactional
    public Policy create(ChangeRequest request, UUID idempotencyKey, RequestMetadata metadata, Authentication authentication) {
        UUID actor = actor(authentication, "SECURITY_POLICY_WRITE");
        validate(request);
        if (idempotencyKey == null) throw new SessionPolicyException("VALIDATION_ERROR");
        String requestHash;
        try { requestHash = protector.hash(mapper.writeValueAsString(request)); }
        catch (Exception exception) { throw new SessionPolicyException("SESSION_POLICY_WRITE_FAILED"); }
        String scope = "session-policy:" + actor;
        int inserted = jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at)
                values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')
                on conflict(scope,idempotency_key) do nothing
                """).param("scope", scope).param("key", idempotencyKey).param("hash", requestHash).update();
        if (inserted == 0) {
            Object[] previous = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                    .param("scope", scope).param("key", idempotencyKey)
                    .query((row, index) -> new Object[] {row.getString(1), row.getString(2), row.getObject(3, UUID.class)}).single();
            if (!requestHash.equals(previous[0])) throw new SessionPolicyException("IDEMPOTENCY_KEY_REUSED");
            if (!"COMPLETED".equals(previous[1]) || previous[2] == null)
                throw new SessionPolicyException("IDEMPOTENCY_IN_PROGRESS");
            return byId((UUID) previous[2]);
        }
        Policy current = jdbc.sql(sql() + " where p.effective_to is null for update")
                .query((row, index) -> map(row)).optional()
                .orElseThrow(() -> new SessionPolicyException("ACTIVE_SESSION_POLICY_MISSING"));
        if (!current.id().equals(request.currentPolicyId())) throw new SessionPolicyException("SESSION_POLICY_CHANGED");
        java.time.OffsetDateTime now = jdbc.sql("select statement_timestamp()").query(java.time.OffsetDateTime.class).single();
        jdbc.sql("update admin_session_policy set effective_to=:now where id=:id and effective_to is null")
                .param("now", now).param("id", current.id()).update();
        UUID id = UUID.randomUUID();
        int version = jdbc.sql("select coalesce(max(version),0)+1 from admin_session_policy").query(Integer.class).single();
        String actorDisplay = jdbc.sql("select display_name from admin_user where id=:actor")
                .param("actor", actor).query(String.class).single();
        jdbc.sql("""
                insert into admin_session_policy(id,version,max_failed_attempts,lock_duration_minutes,
                    idle_timeout_minutes,absolute_timeout_minutes,expiry_warning_minutes,change_reason,
                    effective_from,created_by)
                values(:id,:version,:attempts,:lock,:idle,:absolute,:warning,:reason,:now,:actor)
                """).param("id", id).param("version", version).param("attempts", request.maxFailedAttempts())
                .param("lock", request.lockDurationMinutes()).param("idle", request.idleTimeoutMinutes())
                .param("absolute", request.absoluteTimeoutMinutes()).param("warning", request.expiryWarningMinutes())
                .param("reason", request.changeReason().trim()).param("now", now).param("actor", actor).update();
        Map<String, Object> details = Map.ofEntries(Map.entry("beforeVersion", current.version()), Map.entry("afterVersion", version),
                Map.entry("beforeMaxFailedAttempts", current.maxFailedAttempts()), Map.entry("afterMaxFailedAttempts", request.maxFailedAttempts()),
                Map.entry("beforeLockDurationMinutes", current.lockDurationMinutes()), Map.entry("afterLockDurationMinutes", request.lockDurationMinutes()),
                Map.entry("beforeIdleTimeoutMinutes", current.idleTimeoutMinutes()), Map.entry("afterIdleTimeoutMinutes", request.idleTimeoutMinutes()),
                Map.entry("beforeAbsoluteTimeoutMinutes", current.absoluteTimeoutMinutes()), Map.entry("afterAbsoluteTimeoutMinutes", request.absoluteTimeoutMinutes()),
                Map.entry("beforeExpiryWarningMinutes", current.expiryWarningMinutes()), Map.entry("afterExpiryWarningMinutes", request.expiryWarningMinutes()),
                Map.entry("reason", request.changeReason().trim()));
        audit.record(new Event(now.toInstant(), metadata.requestId(), "MGT-AUTH-SESSION-POLICY", "SECURITY", "ADMIN", actor,
                actorDisplay, "SESSION_POLICY_CHANGED", "SESSION_POLICY", id, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), details));
        jdbc.sql("""
                update idempotency_record set state='COMPLETED',resource_id=:id,response_status=201
                 where scope=:scope and idempotency_key=:key
                """).param("id", id).param("scope", scope).param("key", idempotencyKey).update();
        return byId(id);
    }

    private Policy byId(UUID id) {
        return jdbc.sql(sql() + " where p.id=:id").param("id", id)
                .query((row, index) -> map(row)).single();
    }

    private static void validate(ChangeRequest request) {
        if (request == null || request.currentPolicyId() == null || request.maxFailedAttempts() < 3 || request.maxFailedAttempts() > 10
                || request.lockDurationMinutes() < 10 || request.lockDurationMinutes() > 120
                || request.idleTimeoutMinutes() < 15 || request.idleTimeoutMinutes() > 240
                || request.absoluteTimeoutMinutes() < 240 || request.absoluteTimeoutMinutes() > 1440
                || request.expiryWarningMinutes() < 1 || request.expiryWarningMinutes() > 10
                || request.idleTimeoutMinutes() >= request.absoluteTimeoutMinutes()
                || request.expiryWarningMinutes() >= request.idleTimeoutMinutes()
                || request.changeReason() == null || request.changeReason().trim().length() < 10
                || request.changeReason().trim().length() > 300)
            throw new SessionPolicyException("SESSION_POLICY_OUT_OF_RANGE");
    }

    private static UUID actor(Authentication authentication, String permission) {
        require(authentication, permission);
        try { return UUID.fromString(authentication.getName()); }
        catch (RuntimeException exception) { throw new SessionPolicyException(permission + "_DENIED"); }
    }

    private String sql() {
        return """
                select p.id,p.version,p.max_failed_attempts,p.lock_duration_minutes,p.idle_timeout_minutes,
                       p.absolute_timeout_minutes,p.expiry_warning_minutes,p.change_reason,p.effective_from,
                       p.effective_to,p.created_by,u.display_name
                  from admin_session_policy p join admin_user u on u.id=p.created_by
                """;
    }

    private Policy map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Policy(row.getObject("id", java.util.UUID.class), row.getInt("version"),
                row.getInt("max_failed_attempts"), row.getInt("lock_duration_minutes"),
                row.getInt("idle_timeout_minutes"), row.getInt("absolute_timeout_minutes"),
                row.getInt("expiry_warning_minutes"), row.getString("change_reason"),
                row.getObject("effective_from", java.time.OffsetDateTime.class),
                row.getObject("effective_to", java.time.OffsetDateTime.class),
                row.getObject("created_by", java.util.UUID.class), row.getString("display_name"));
    }

    private String encode(int version) {
        String value = Integer.toString(version);
        String signed = value + "|" + protector.hash("session-policy-cursor:v1|" + value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    private Integer decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2 || !java.security.MessageDigest.isEqual(
                    protector.hash("session-policy-cursor:v1|" + parts[0]).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            int version = Integer.parseInt(parts[0]);
            if (version < 1) throw new IllegalArgumentException();
            return version;
        } catch (RuntimeException exception) { throw new SessionPolicyException("SESSION_POLICY_CURSOR_INVALID"); }
    }

    private static void require(Authentication authentication, String permission) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> permission.equals(authority.getAuthority())))
            throw new SessionPolicyException(permission + "_DENIED");
    }

    public record ChangeRequest(UUID currentPolicyId, int maxFailedAttempts, int lockDurationMinutes,
            int idleTimeoutMinutes, int absoluteTimeoutMinutes, int expiryWarningMinutes, String changeReason) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}

    public static final class SessionPolicyException extends RuntimeException {
        private final String code;
        public SessionPolicyException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
