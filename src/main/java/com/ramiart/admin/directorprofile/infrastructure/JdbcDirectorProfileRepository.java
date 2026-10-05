package com.ramiart.admin.directorprofile.infrastructure;

import com.ramiart.admin.directorprofile.application.DirectorProfileException;
import static com.ramiart.admin.directorprofile.application.DirectorProfileModels.*;
import com.ramiart.admin.directorprofile.application.DirectorProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDirectorProfileRepository implements DirectorProfileRepository {
    private static final RowMapper<StoredProfile> PROFILE = JdbcDirectorProfileRepository::mapProfile;
    private final JdbcClient jdbc;
    public JdbcDirectorProfileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<StoredProfile> findByStatus(String status) {
        return jdbc.sql(selectProfile() + " where status=:status order by revision desc limit 1")
                .param("status", status).query(PROFILE).optional();
    }
    @Override public Optional<StoredProfile> findById(UUID id) {
        return jdbc.sql(selectProfile() + " where id=:id").param("id", id).query(PROFILE).optional();
    }
    @Override public Optional<StoredProfile> findByIdForUpdate(UUID id) {
        return jdbc.sql(selectProfile() + " where id=:id for update").param("id", id).query(PROFILE).optional();
    }
    @Override public List<Career> careers(UUID profileId) {
        return jdbc.sql("""
                select id,period,title,display_order,hidden from director_career
                where director_profile_id=:profileId order by display_order,id
                """).param("profileId", profileId).query((rs, n) -> new Career(rs.getObject("id", UUID.class),
                rs.getString("period"), rs.getString("title"), rs.getInt("display_order"), rs.getBoolean("hidden"))).list();
    }
    @Override public int maxRevision() {
        return jdbc.sql("select coalesce(max(revision),0) from director_profile").query(Integer.class).single();
    }
    @Override public UUID insertDraft(Write write, UUID actor, UUID basedOn) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into director_profile(id,revision,status,based_on_profile_id,name,title,introduction,created_by)
                values(:id,:revision,'DRAFT',:based,:name,:title,:introduction,:actor)
                """).param("id", id).param("revision", maxRevision() + 1).param("based", basedOn)
                .param("name", write.name()).param("title", write.title()).param("introduction", write.introduction())
                .param("actor", actor).update();
        return id;
    }
    @Override public int updateDraft(UUID id, Write write) {
        return jdbc.sql("""
                update director_profile set name=:name,title=:title,introduction=:introduction,version=version+1
                where id=:id and status='DRAFT' and version=:version
                """).param("name", write.name()).param("title", write.title()).param("introduction", write.introduction())
                .param("id", id).param("version", write.version()).update();
    }
    @Override public void replaceCareers(UUID profileId, List<Career> careers) {
        jdbc.sql("delete from director_career where director_profile_id=:id").param("id", profileId).update();
        for (Career career : careers) {
            jdbc.sql("""
                    insert into director_career(id,director_profile_id,period,title,display_order,hidden)
                    values(:id,:profileId,:period,:title,:order,:hidden)
                    """).param("id", career.id()).param("profileId", profileId).param("period", career.period())
                    .param("title", career.title()).param("order", career.displayOrder()).param("hidden", career.hidden()).update();
        }
    }
    @Override public void publish(UUID id, long version, UUID actor) {
        jdbc.sql("update director_profile set status='ARCHIVED' where status='PUBLISHED'").update();
        int changed = jdbc.sql("""
                update director_profile set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),
                  version=version+1 where id=:id and status='DRAFT' and version=:version
                """).param("actor", actor).param("id", id).param("version", version).update();
        if (changed != 1) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
    }
    @Override public Claim claim(String scope, UUID key, String hash) {
        int inserted = jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,expires_at)
                values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing
                """).param("scope", scope).param("key", key).param("hash", hash).update();
        if (inserted == 1) return new Claim(true, null);
        var row = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope", scope).param("key", key)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)}).single();
        if (!hash.equals(row[0])) throw new DirectorProfileException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(row[1]) || row[2] == null) throw new DirectorProfileException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false, (UUID) row[2]);
    }
    @Override public void complete(String scope, UUID key, UUID resourceId, int status) {
        jdbc.sql("""
                update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status
                where scope=:scope and idempotency_key=:key
                """).param("id", resourceId).param("status", status).param("scope", scope).param("key", key).update();
    }
    private static String selectProfile() {
        return "select id,revision,status,version,name,title,introduction,updated_at,published_at from director_profile";
    }
    private static StoredProfile mapProfile(ResultSet rs, int row) throws SQLException {
        return new StoredProfile(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status"),
                rs.getLong("version"), rs.getString("name"), rs.getString("title"), rs.getString("introduction"),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("published_at") == null
                        ? null : rs.getTimestamp("published_at").toInstant());
    }
}
