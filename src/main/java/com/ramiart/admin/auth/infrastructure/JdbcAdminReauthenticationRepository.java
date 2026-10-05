package com.ramiart.admin.auth.infrastructure;

import com.ramiart.admin.auth.application.AdminReauthenticationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminReauthenticationRepository implements AdminReauthenticationRepository {
    private final JdbcClient jdbc;
    public JdbcAdminReauthenticationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    @Override public void issue(UUID id,UUID user,UUID session,String purpose,String hash,Instant expiresAt) {
        jdbc.sql("insert into admin_reauthentication(id,admin_user_id,admin_session_id,purpose,token_hash,expires_at) values(:id,:user,:session,:purpose,:hash,:expires)")
                .param("id",id).param("user",user).param("session",session).param("purpose",purpose).param("hash",hash).param("expires",JdbcTimestamps.offsetDateTime(expiresAt)).update();
    }
    @Override public boolean consume(String hash,UUID user,UUID session,String purpose,Instant now) {
        return jdbc.sql("update admin_reauthentication set consumed_at=:now where token_hash=:hash and admin_user_id=:user and admin_session_id=:session and purpose=:purpose and consumed_at is null and expires_at>:now")
                .param("now",JdbcTimestamps.offsetDateTime(now)).param("hash",hash).param("user",user).param("session",session).param("purpose",purpose).update()==1;
    }
}
