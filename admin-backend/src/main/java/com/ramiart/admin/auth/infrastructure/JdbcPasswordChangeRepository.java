package com.ramiart.admin.auth.infrastructure;

import com.ramiart.admin.auth.application.PasswordChangeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPasswordChangeRepository implements PasswordChangeRepository {

    private final JdbcClient jdbcClient;

    public JdbcPasswordChangeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<String> findRecentPasswordHashes(UUID userId, int limit) {
        return jdbcClient.sql("""
                        select password_hash
                        from admin_password_history
                        where admin_user_id = :user_id
                        order by changed_at desc, id desc
                        limit :limit
                        """)
                .param("user_id", userId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    @Override
    public boolean isCompromisedPassword(String passwordFingerprint) {
        return jdbcClient.sql("""
                        select exists(
                            select 1 from admin_compromised_password
                            where password_sha256 = :password_sha256
                        )
                        """)
                .param("password_sha256", passwordFingerprint)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<PasswordAccount> findBySessionForUpdate(String tokenHash) {
        return jdbcClient.sql("""
                        select u.id as user_id, s.id as session_id, u.display_name, u.status,
                               u.password_hash, u.version, p.idle_timeout_minutes,
                               s.expires_at, s.idle_expires_at, s.revoked_at
                        from admin_session s
                        join admin_user u on u.id = s.admin_user_id
                        join admin_session_policy p on p.id = s.policy_id
                        where s.token_hash = :token_hash
                        for update of u, s
                        """)
                .param("token_hash", tokenHash)
                .query((resultSet, rowNumber) -> new PasswordAccount(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("status"),
                        resultSet.getString("password_hash"),
                        resultSet.getLong("version"),
                        resultSet.getInt("idle_timeout_minutes"),
                        JdbcTimestamps.instant(resultSet, "expires_at"),
                        JdbcTimestamps.instant(resultSet, "idle_expires_at"),
                        JdbcTimestamps.instant(resultSet, "revoked_at")))
                .optional();
    }

    @Override
    public boolean changePassword(UUID userId, long version, String passwordHash, Instant changedAt) {
        return jdbcClient.sql("""
                        update admin_user
                        set password_hash = :password_hash,
                            password_must_change = false,
                            temporary_password_expires_at = null,
                            password_changed_at = :changed_at,
                            failed_login_count = 0,
                            updated_at = :changed_at,
                            version = version + 1
                        where id = :user_id and version = :version and status = 'ACTIVE'
                        """)
                .param("password_hash", passwordHash)
                .param("changed_at", JdbcTimestamps.offsetDateTime(changedAt))
                .param("user_id", userId)
                .param("version", version)
                .update() == 1;
    }

    @Override
    public void addPasswordHistory(UUID userId, String passwordHash, Instant changedAt) {
        jdbcClient.sql("""
                        insert into admin_password_history (admin_user_id, password_hash, changed_at)
                        values (:user_id, :password_hash, :changed_at)
                        """)
                .param("user_id", userId)
                .param("password_hash", passwordHash)
                .param("changed_at", JdbcTimestamps.offsetDateTime(changedAt))
                .update();
    }

    @Override
    public void prunePasswordHistory(UUID userId, int retainedCount) {
        jdbcClient.sql("""
                        delete from admin_password_history
                        where admin_user_id = :user_id
                          and id not in (
                              select id from admin_password_history
                              where admin_user_id = :user_id
                              order by changed_at desc, id desc
                              limit :retained_count
                          )
                        """)
                .param("user_id", userId)
                .param("retained_count", retainedCount)
                .update();
    }

    @Override
    public int revokeOtherSessions(UUID userId, UUID currentSessionId, Instant revokedAt) {
        return jdbcClient.sql("""
                        update admin_session
                        set revoked_at = :revoked_at, revoke_reason = 'PASSWORD_CHANGED'
                        where admin_user_id = :user_id
                          and id <> :current_session_id
                          and revoked_at is null
                        """)
                .param("revoked_at", JdbcTimestamps.offsetDateTime(revokedAt))
                .param("user_id", userId)
                .param("current_session_id", currentSessionId)
                .update();
    }

    @Override
    public void updateCurrentSessionActivity(UUID sessionId, Instant lastSeenAt, Instant idleExpiresAt) {
        int updated = jdbcClient.sql("""
                        update admin_session
                        set last_seen_at = greatest(last_seen_at, :last_seen_at),
                            idle_expires_at = greatest(idle_expires_at, :idle_expires_at)
                        where id = :session_id and revoked_at is null and expires_at > :last_seen_at
                        """)
                .param("last_seen_at", JdbcTimestamps.offsetDateTime(lastSeenAt))
                .param("idle_expires_at", JdbcTimestamps.offsetDateTime(idleExpiresAt))
                .param("session_id", sessionId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("current session activity update failed");
        }
    }
}
