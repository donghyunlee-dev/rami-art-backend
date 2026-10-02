package com.ramiart.admin.auth.infrastructure;

import com.ramiart.admin.auth.application.AuthSessionRepository;
import com.ramiart.admin.auth.domain.AdminAccount;
import com.ramiart.admin.auth.domain.AdminAccount.AccountStatus;
import com.ramiart.admin.auth.domain.EmailAddress;
import com.ramiart.admin.auth.domain.SessionPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthSessionRepository implements AuthSessionRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuthSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public SessionPolicy findActivePolicy() {
        return jdbcClient.sql("""
                        select id, max_failed_attempts, lock_duration_minutes,
                               idle_timeout_minutes, absolute_timeout_minutes, expiry_warning_minutes
                        from admin_session_policy
                        where effective_to is null and effective_from <= now()
                        order by effective_from desc
                        limit 1
                        """)
                .query(this::mapPolicy)
                .single();
    }

    @Override
    public Optional<LoginAccount> findAccountForLogin(EmailAddress email) {
        return jdbcClient.sql("""
                        select u.id, u.email, u.display_name, u.status, u.locked_until,
                               u.password_must_change, u.temporary_password_expires_at,
                               u.password_hash, r.id as role_id, r.code as role_code
                        from admin_user u
                        join admin_user_role ur on ur.admin_user_id = u.id
                        join admin_role r on r.id = ur.admin_role_id and r.active = true
                        where lower(u.email) = :email
                        for update of u
                        """)
                .param("email", email.value())
                .query((resultSet, rowNumber) -> new LoginAccount(
                        mapAccount(resultSet, "id"),
                        resultSet.getString("password_hash"),
                        resultSet.getObject("role_id", UUID.class),
                        resultSet.getString("role_code")))
                .optional();
    }

    @Override
    public boolean recordFailedLogin(UUID accountId, SessionPolicy policy, Instant now) {
        return jdbcClient.sql("""
                        update admin_user
                        set failed_login_count = least(failed_login_count + 1, 10),
                            status = case
                                when failed_login_count + 1 >= :threshold then 'LOCKED'
                                else 'ACTIVE'
                            end,
                            locked_until = case
                                when failed_login_count + 1 >= :threshold then :locked_until
                                else null
                            end,
                            updated_at = :now,
                            version = version + 1
                        where id = :id and status <> 'INACTIVE'
                        returning status
                        """)
                .param("threshold", policy.failedLoginThreshold())
                .param("locked_until", JdbcTimestamps.offsetDateTime(now.plus(policy.lockDuration())))
                .param("now", JdbcTimestamps.offsetDateTime(now))
                .param("id", accountId)
                .query(String.class)
                .optional()
                .map("LOCKED"::equals)
                .orElse(false);
    }

    @Override
    public UUID issueSession(
            LoginAccount account,
            SessionPolicy policy,
            String tokenHash,
            SessionPolicy.SessionWindow window,
            RequestMetadata metadata,
            Instant now) {
        jdbcClient.sql("""
                        update admin_user
                        set failed_login_count = 0, status = 'ACTIVE', locked_until = null,
                            last_login_at = :now, updated_at = :now, version = version + 1
                        where id = :id
                        """)
                .param("now", JdbcTimestamps.offsetDateTime(now))
                .param("id", account.account().id())
                .update();

        return jdbcClient.sql("""
                        insert into admin_session (
                            admin_user_id, policy_id, token_hash, issued_at, last_seen_at,
                            expires_at, idle_expires_at, ip_address, user_agent
                        ) values (
                            :user_id, :policy_id, :token_hash, :now, :now,
                            :expires_at, :idle_expires_at, cast(:ip_address as inet), :user_agent
                        )
                        returning id
                        """)
                .param("user_id", account.account().id())
                .param("policy_id", policy.id())
                .param("token_hash", tokenHash)
                .param("now", JdbcTimestamps.offsetDateTime(now))
                .param("expires_at", JdbcTimestamps.offsetDateTime(window.absoluteExpiresAt()))
                .param("idle_expires_at", JdbcTimestamps.offsetDateTime(window.idleExpiresAt()))
                .param("ip_address", metadata.ipAddress())
                .param("user_agent", metadata.userAgent())
                .query(UUID.class)
                .single();
    }

    @Override
    public Optional<StoredSession> findSession(String tokenHash, boolean lockForUpdate) {
        String lockClause = lockForUpdate ? " for update of s" : "";
        return jdbcClient.sql("""
                        select s.id, s.expires_at, s.idle_expires_at, s.revoked_at,
                               u.id as user_id, u.email, u.display_name, u.status, u.locked_until,
                               u.password_must_change, u.temporary_password_expires_at,
                               r.id as role_id, r.code as role_code,
                               p.id as policy_id, p.max_failed_attempts, p.lock_duration_minutes,
                               p.idle_timeout_minutes, p.absolute_timeout_minutes, p.expiry_warning_minutes
                        from admin_session s
                        join admin_user u on u.id = s.admin_user_id
                        join admin_user_role ur on ur.admin_user_id = u.id
                        join admin_role r on r.id = ur.admin_role_id and r.active = true
                        join admin_session_policy p on p.id = s.policy_id
                        where s.token_hash = :token_hash
                        """ + lockClause)
                .param("token_hash", tokenHash)
                .query((resultSet, rowNumber) -> new StoredSession(
                        resultSet.getObject("id", UUID.class),
                        mapAccount(resultSet, "user_id"),
                        resultSet.getObject("role_id", UUID.class),
                        resultSet.getString("role_code"),
                        mapPolicy(resultSet, "policy_id"),
                        JdbcTimestamps.instant(resultSet, "expires_at"),
                        JdbcTimestamps.instant(resultSet, "idle_expires_at"),
                        JdbcTimestamps.instant(resultSet, "revoked_at")))
                .optional();
    }

    @Override
    public List<String> findPermissions(UUID roleId) {
        return jdbcClient.sql("""
                        select p.code
                        from admin_role_permission rp
                        join admin_permission p on p.id = rp.admin_permission_id and p.active = true
                        where rp.admin_role_id = :role_id
                        order by p.code
                        """)
                .param("role_id", roleId)
                .query(String.class)
                .list();
    }

    @Override
    public void updateSessionActivity(UUID sessionId, Instant lastSeenAt, Instant idleExpiresAt) {
        int updated = jdbcClient.sql("""
                        update admin_session
                        set last_seen_at = greatest(last_seen_at, :last_seen_at),
                            idle_expires_at = greatest(idle_expires_at, :idle_expires_at)
                        where id = :id and revoked_at is null and expires_at > :last_seen_at
                        """)
                .param("last_seen_at", JdbcTimestamps.offsetDateTime(lastSeenAt))
                .param("idle_expires_at", JdbcTimestamps.offsetDateTime(idleExpiresAt))
                .param("id", sessionId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("session activity update failed");
        }
    }

    @Override
    public boolean revokeSession(UUID sessionId, Instant revokedAt) {
        return jdbcClient.sql("""
                        update admin_session
                        set revoked_at = :revoked_at, revoke_reason = 'LOGOUT'
                        where id = :id and revoked_at is null
                        """)
                .param("revoked_at", JdbcTimestamps.offsetDateTime(revokedAt))
                .param("id", sessionId)
                .update() == 1;
    }

    private SessionPolicy mapPolicy(ResultSet resultSet, int rowNumber) throws SQLException {
        return mapPolicy(resultSet, "id");
    }

    private SessionPolicy mapPolicy(ResultSet resultSet, String idColumn) throws SQLException {
        return new SessionPolicy(
                resultSet.getObject(idColumn, UUID.class),
                resultSet.getInt("max_failed_attempts"),
                Duration.ofMinutes(resultSet.getInt("lock_duration_minutes")),
                Duration.ofMinutes(resultSet.getInt("idle_timeout_minutes")),
                Duration.ofMinutes(resultSet.getInt("absolute_timeout_minutes")),
                Duration.ofMinutes(resultSet.getInt("expiry_warning_minutes")));
    }

    private AdminAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return mapAccount(resultSet, "id");
    }

    private AdminAccount mapAccount(ResultSet resultSet, String idColumn) throws SQLException {
        return new AdminAccount(
                resultSet.getObject(idColumn, UUID.class),
                EmailAddress.of(resultSet.getString("email")),
                resultSet.getString("display_name"),
                AccountStatus.valueOf(resultSet.getString("status")),
                JdbcTimestamps.instant(resultSet, "locked_until"),
                resultSet.getBoolean("password_must_change"),
                JdbcTimestamps.instant(resultSet, "temporary_password_expires_at"));
    }
}
