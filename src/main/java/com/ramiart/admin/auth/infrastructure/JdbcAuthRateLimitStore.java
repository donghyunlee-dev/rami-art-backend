package com.ramiart.admin.auth.infrastructure;

import com.ramiart.admin.auth.application.AuthRateLimitStore;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAuthRateLimitStore implements AuthRateLimitStore {
    private final JdbcClient jdbc;

    public JdbcAuthRateLimitStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long consume(String bucketHash, int limit) {
        jdbc.sql("delete from admin_auth_rate_limit where expires_at < now() - interval '1 day'").update();
        return jdbc.sql("""
                insert into admin_auth_rate_limit (bucket_hash, attempts, expires_at)
                values (:hash, 1, now() + interval '15 minutes')
                on conflict (bucket_hash) do update set
                    attempts = case when admin_auth_rate_limit.expires_at <= now() then 1
                        else least(admin_auth_rate_limit.attempts + 1, :limit + 1) end,
                    expires_at = case when admin_auth_rate_limit.expires_at <= now()
                        then now() + interval '15 minutes' else admin_auth_rate_limit.expires_at end
                returning case when attempts > :limit
                    then greatest(1, ceil(extract(epoch from expires_at - now()))::bigint) else 0 end
                """).param("hash", bucketHash).param("limit", limit).query(Long.class).single();
    }
}
