package com.ramiart.admin.privacy.infrastructure;

import static com.ramiart.admin.privacy.application.PublicPrivacyPolicyModels.*;
import com.ramiart.admin.privacy.application.PublicPrivacyPolicyRepository;
import com.ramiart.admin.privacy.application.PublicPrivacyPolicyException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPublicPrivacyPolicyRepository implements PublicPrivacyPolicyRepository {
    private static final RowMapper<Policy> MAPPER = JdbcPublicPrivacyPolicyRepository::map;
    private final JdbcClient jdbc;

    public JdbcPublicPrivacyPolicyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public List<Policy> findAll() {
        return jdbc.sql(select() + " order by revision desc").query(MAPPER).list();
    }
    @Override public Optional<Policy> findById(UUID id) {
        return jdbc.sql(select() + " where id=:id").param("id", id).query(MAPPER).optional();
    }
    @Override public Optional<Policy> findByIdForUpdate(UUID id) {
        return jdbc.sql(select() + " where id=:id for update").param("id", id).query(MAPPER).optional();
    }
    @Override public Optional<Policy> findPublished() {
        return jdbc.sql(select() + " where status='PUBLISHED'").query(MAPPER).optional();
    }
    @Override public Optional<Policy> findPublishedByVersion(String code) {
        return jdbc.sql(select() + " where status='PUBLISHED' and version_code=:code")
                .param("code", code).query(MAPPER).optional();
    }
    @Override public int nextRevision() {
        return jdbc.sql("select coalesce(max(revision),0)+1 from public_privacy_policy")
                .query(Integer.class).single();
    }
    @Override public UUID insertDraft(Write write, UUID actor, UUID basedOn) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into public_privacy_policy(id,revision,status,based_on_policy_id,version_code,title,
                  collection_items,purpose,retention_months,retention_anchor,contact_email,effective_on,created_by)
                values(:id,:revision,'DRAFT',:based,:code,:title,:items,:purpose,:months,:anchor,:email,:effective,:actor)
                """).param("id", id).param("revision", nextRevision()).param("based", basedOn)
                .param("code", write.versionCode()).param("title", write.title())
                .param("items", write.collectionItems()).param("purpose", write.purpose())
                .param("months", write.retentionMonths()).param("anchor", write.retentionAnchor())
                .param("email", write.contactEmail()).param("effective", write.effectiveOn())
                .param("actor", actor).update();
        return id;
    }
    @Override public int updateDraft(UUID id, Write write) {
        return jdbc.sql("""
                update public_privacy_policy set version_code=:code,title=:title,collection_items=:items,
                  purpose=:purpose,retention_months=:months,retention_anchor=:anchor,contact_email=:email,
                  effective_on=:effective,version=version+1
                where id=:id and status='DRAFT' and version=:version
                """).param("code", write.versionCode()).param("title", write.title())
                .param("items", write.collectionItems()).param("purpose", write.purpose())
                .param("months", write.retentionMonths()).param("anchor", write.retentionAnchor())
                .param("email", write.contactEmail()).param("effective", write.effectiveOn())
                .param("id", id).param("version", write.version()).update();
    }
    @Override public void publish(UUID id, long version, UUID actor) {
        jdbc.sql("update public_privacy_policy set status='ARCHIVED' where status='PUBLISHED'").update();
        int updated = jdbc.sql("""
                update public_privacy_policy set status='PUBLISHED',published_by=:actor,
                  published_at=statement_timestamp(),version=version+1
                where id=:id and status='DRAFT' and version=:version
                """).param("actor", actor).param("id", id).param("version", version).update();
        if (updated != 1) throw new PublicPrivacyPolicyException("PRIVACY_POLICY_VERSION_CONFLICT");
    }
    @Override public Claim claim(String scope, UUID key, String hash) {
        int inserted = jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,expires_at)
                values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing
                """).param("scope", scope).param("key", key).param("hash", hash).update();
        if (inserted == 1) return new Claim(true, null);
        var row = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope", scope).param("key", key)
                .query((rs,n) -> new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if (!hash.equals(row[0])) throw new PublicPrivacyPolicyException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(row[1]) || row[2] == null) throw new PublicPrivacyPolicyException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false, (UUID) row[2]);
    }
    @Override public void complete(String scope, UUID key, UUID id, int status) {
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
                .param("id", id).param("status", status).param("scope", scope).param("key", key).update();
    }

    private static String select() {
        return """
                select id,revision,status,version_code,title,collection_items,purpose,retention_months,
                  retention_anchor,contact_email,effective_on,version,created_at,published_by,published_at
                from public_privacy_policy
                """;
    }
    private static Policy map(ResultSet rs, int row) throws SQLException {
        return new Policy(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status"),
                rs.getString("version_code"), rs.getString("title"), rs.getString("collection_items"),
                rs.getString("purpose"), rs.getInt("retention_months"), rs.getString("retention_anchor"),
                rs.getString("contact_email"), rs.getObject("effective_on", java.time.LocalDate.class),
                rs.getLong("version"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("published_by", UUID.class), rs.getObject("published_at", java.time.OffsetDateTime.class));
    }
}
