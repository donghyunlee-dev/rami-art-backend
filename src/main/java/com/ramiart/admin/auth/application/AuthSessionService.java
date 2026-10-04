package com.ramiart.admin.auth.application;

import com.ramiart.admin.auth.application.AuthSessionRepository.LoginAccount;
import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.AuthSessionRepository.StoredSession;
import com.ramiart.admin.auth.domain.AdminAccount.LoginAvailability;
import com.ramiart.admin.auth.domain.EmailAddress;
import com.ramiart.admin.auth.domain.SessionPolicy;
import com.ramiart.admin.auth.domain.SessionToken;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {

    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private static final String PASSWORD_CHANGE_PATH = "/admin/settings/password?required=true";

    private final AuthSessionRepository repository;
    private final AuditRecorder auditRecorder;
    private final PasswordVerifier passwordVerifier;
    private final Clock clock;
    private final AuthRateLimiter rateLimiter;

    public AuthSessionService(
            AuthSessionRepository repository,
            AuditRecorder auditRecorder,
            PasswordVerifier passwordVerifier,
            Clock clock, AuthRateLimiter rateLimiter) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
        this.passwordVerifier = passwordVerifier;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(noRollbackFor = AuthSessionException.class)
    public IssuedSession login(String email, String password, String returnUrl, RequestMetadata metadata) {
        Instant now = clock.instant();
        rateLimiter.check("login-ip", metadata.ipAddress(), 100);
        rateLimiter.check("login-email", EmailAddress.of(email).value(), 10);
        SessionPolicy policy = repository.findActivePolicy();
        Optional<LoginAccount> foundAccount = repository.findAccountForLogin(EmailAddress.of(email));
        String hash = foundAccount.map(LoginAccount::passwordHash).orElseGet(passwordVerifier::dummyHash);
        boolean passwordMatches = passwordVerifier.matches(password, hash);

        if (foundAccount.isEmpty()) {
            recordLoginAudit(null, metadata, now, "FAILURE", "AUTHENTICATION_FAILED");
            throw new AuthSessionException("AUTHENTICATION_FAILED");
        }

        LoginAccount account = foundAccount.get();
        if (!passwordMatches) {
            boolean locked = repository.recordFailedLogin(account.account().id(), policy, now);
            String reason = locked ? "ACCOUNT_LOCKED" : "AUTHENTICATION_FAILED";
            recordLoginAudit(account, metadata, now, "FAILURE", reason);
            throw new AuthSessionException(reason);
        }

        LoginAvailability availability = account.account().loginAvailabilityAt(now);
        if (availability != LoginAvailability.ALLOWED) {
            String reason = switch (availability) {
                case LOCKED -> "ACCOUNT_LOCKED";
                case TEMPORARY_PASSWORD_EXPIRED -> "TEMPORARY_PASSWORD_EXPIRED";
                case INACTIVE -> "AUTHENTICATION_FAILED";
                case ALLOWED -> throw new IllegalStateException("unreachable login availability");
            };
            recordLoginAudit(account, metadata, now, "FAILURE", reason);
            throw new AuthSessionException(reason);
        }

        String rawToken = SessionToken.generate();
        SessionPolicy.SessionWindow window = policy.issueAt(now);
        UUID sessionId = repository.issueSession(
                account, policy, SessionToken.sha256(rawToken), window, metadata, now);
        recordLoginAudit(account, metadata, now, "SUCCESS", null);

        boolean passwordMustChange = account.account().passwordMustChange();
        String redirectTo = passwordMustChange ? PASSWORD_CHANGE_PATH : safeReturnUrl(returnUrl);
        return new IssuedSession(
                sessionId,
                rawToken,
                account.account().id(),
                account.account().displayName(),
                account.roleCode(),
                passwordMustChange,
                window.absoluteExpiresAt(),
                redirectTo);
    }

    @Transactional(readOnly = true)
    public CurrentContext current(String rawToken) {
        StoredSession session = requireActiveSession(rawToken, false);
        List<String> permissions = repository.findPermissions(session.roleId());
        String firstAllowedPath = firstAllowedPath(permissions, session.account().passwordMustChange());
        if (firstAllowedPath == null) {
            throw new AuthSessionException("ADMIN_ACCESS_DENIED");
        }
        return toCurrentContext(session, permissions, firstAllowedPath);
    }

    @Transactional
    public SessionContext extend(String rawToken, RequestMetadata metadata) {
        Instant now = clock.instant();
        StoredSession session = requireActiveSession(rawToken, true);
        SessionPolicy.SessionWindow window;
        try {
            window = session.policy().extendAt(now, session.expiresAt());
        } catch (IllegalStateException exception) {
            throw new AuthSessionException("SESSION_NOT_EXTENDABLE");
        }
        repository.updateSessionActivity(session.id(), now, window.idleExpiresAt());
        auditRecorder.record(new AuditRecorder.Event(
                now, metadata.requestId(), "MGT-AUTH-SESSION-EXPIRE", "SECURITY", "ADMIN",
                session.account().id(), session.account().displayName(), "ADMIN_SESSION_EXTENDED",
                "ADMIN_SESSION", session.id(), "SUCCESS", null, metadata.ipAddress(),
                metadata.userAgent(), Map.of()));
        return new SessionContext(window.absoluteExpiresAt(), window.idleExpiresAt(), window.warningAt());
    }

    @Transactional
    public void logout(String rawToken, RequestMetadata metadata) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Optional<StoredSession> found = repository.findSession(SessionToken.sha256(rawToken), true);
        if (found.isEmpty()) {
            return;
        }
        StoredSession session = found.get();
        Instant now = clock.instant();
        if (session.revokedAt() == null && repository.revokeSession(session.id(), now)) {
            auditRecorder.record(new AuditRecorder.Event(
                    now, metadata.requestId(), "MGT-AUTH-LOGOUT", "SECURITY", "ADMIN",
                    session.account().id(), session.account().displayName(), "ADMIN_LOGOUT",
                    "ADMIN_SESSION", session.id(), "SUCCESS", null, metadata.ipAddress(),
                    metadata.userAgent(), Map.of()));
        }
    }

    private StoredSession requireActiveSession(String rawToken, boolean lockForUpdate) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthSessionException("SESSION_REQUIRED");
        }
        StoredSession session = repository.findSession(SessionToken.sha256(rawToken), lockForUpdate)
                .orElseThrow(() -> new AuthSessionException("SESSION_EXPIRED"));
        Instant now = clock.instant();
        if (session.revokedAt() != null) {
            throw new AuthSessionException("SESSION_REVOKED");
        }
        if (!session.expiresAt().isAfter(now) || !session.idleExpiresAt().isAfter(now)) {
            throw new AuthSessionException("SESSION_EXPIRED");
        }
        if (session.account().loginAvailabilityAt(now) != LoginAvailability.ALLOWED) {
            throw new AuthSessionException("SESSION_REVOKED");
        }
        return session;
    }

    private CurrentContext toCurrentContext(
            StoredSession session, List<String> permissions, String firstAllowedPath) {
        Instant warningAt = session.idleExpiresAt().minus(session.policy().warningLeadTime());
        return new CurrentContext(
                new UserContext(
                        session.account().id(), session.account().displayName(), session.roleCode(),
                        permissions, session.account().passwordMustChange()),
                new SessionContext(session.expiresAt(), session.idleExpiresAt(), warningAt),
                firstAllowedPath);
    }

    private String firstAllowedPath(List<String> permissions, boolean passwordMustChange) {
        if (passwordMustChange) {
            return PASSWORD_CHANGE_PATH;
        }
        LocalDate today = LocalDate.now(clock.withZone(STUDIO_ZONE));
        String month = today.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("DASHBOARD_READ", "/admin/dashboard");
        routes.put("STUDENT_READ", "/admin/students");
        routes.put("ENROLLMENT_READ", "/admin/enrollments");
        routes.put("SCHEDULE_READ", "/admin/operations/schedules?month=" + month);
        routes.put("ATTENDANCE_READ", "/admin/operations/attendance?date=" + today);
        routes.put("COURSE_READ", "/admin/operations/courses");
        routes.put("STAFF_READ", "/admin/operations/staff");
        routes.put("TUITION_POLICY_READ", "/admin/tuition/policies?year=" + today.getYear() + "&mode=published");
        routes.put("TUITION_BILLING_READ", "/admin/tuition/billings?month=" + month);
        routes.put("FINANCE_READ", "/admin/finance/ledger");
        routes.put("CONTENT_PROFILE_READ", "/admin/content/studio-profile?mode=published");
        routes.put("CONTENT_PROGRAM_READ", "/admin/content/class-programs");
        routes.put("GALLERY_READ", "/admin/content/gallery");
        routes.put("BLOG_READ", "/admin/content/blog");
        routes.put("SITE_BRAND_READ", "/admin/settings/brand");
        routes.put("INQUIRY_READ", "/admin/inquiries");
        routes.put("ADMIN_ACCOUNT_READ", "/admin/settings/admin-users");
        routes.put("SECURITY_POLICY_READ", "/admin/settings/security-policy");
        routes.put("AUDIT_READ", "/admin/settings/audit-logs");
        return routes.entrySet().stream()
                .filter(entry -> permissions.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String safeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return "/admin/dashboard";
        }
        if (!returnUrl.startsWith("/admin") || returnUrl.startsWith("//")
                || returnUrl.contains("\\") || returnUrl.contains("\r") || returnUrl.contains("\n")) {
            return "/admin/dashboard";
        }
        return returnUrl;
    }

    private void recordLoginAudit(
            LoginAccount account,
            RequestMetadata metadata,
            Instant now,
            String result,
            String reasonCode) {
        boolean authenticated = account != null && "SUCCESS".equals(result);
        auditRecorder.record(new AuditRecorder.Event(
                now,
                metadata.requestId(),
                "MGT-AUTH-LOGIN",
                "SECURITY",
                authenticated ? "ADMIN" : "ANONYMOUS",
                authenticated ? account.account().id() : null,
                authenticated ? account.account().displayName() : null,
                "ADMIN_LOGIN",
                authenticated ? "ADMIN_USER" : null,
                authenticated ? account.account().id() : null,
                result,
                reasonCode,
                metadata.ipAddress(),
                metadata.userAgent(),
                Map.of()));
    }

    public record IssuedSession(
            UUID sessionId,
            String rawToken,
            UUID userId,
            String displayName,
            String role,
            boolean passwordMustChange,
            Instant expiresAt,
            String redirectTo) {
    }

    public record UserContext(
            UUID id,
            String displayName,
            String role,
            List<String> permissions,
            boolean passwordMustChange) {
    }

    public record SessionContext(Instant expiresAt, Instant idleExpiresAt, Instant warningAt) {
    }

    public record CurrentContext(UserContext user, SessionContext session, String firstAllowedPath) {
    }
}
