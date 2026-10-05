package com.ramiart.admin.studioprofile.infrastructure;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.studioprofile.application.StudioProfileException;
import com.ramiart.admin.studioprofile.application.StudioProfileModels.StoredProfile;
import com.ramiart.admin.studioprofile.application.StudioProfileModels.Write;
import com.ramiart.admin.studioprofile.application.StudioProfileRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStudioProfileRepository implements StudioProfileRepository {
    private static final RowMapper<StoredProfile> MAPPER = JdbcStudioProfileRepository::map;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcStudioProfileRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public Optional<StoredProfile> findByStatus(String status) {
        return jdbc.sql(select() + " where status=:status order by revision desc limit 1")
                .param("status", status).query(MAPPER).optional();
    }

    @Override public Optional<StoredProfile> findById(UUID id) {
        return jdbc.sql(select() + " where id=:id").param("id", id).query(MAPPER).optional();
    }

    @Override public Optional<StoredProfile> findByIdForUpdate(UUID id) {
        return jdbc.sql(select() + " where id=:id for update").param("id", id).query(MAPPER).optional();
    }

    @Override public int maxRevision() {
        return jdbc.sql("select coalesce(max(revision),0) from studio_profile").query(Integer.class).single();
    }

    @Override public UUID insertDraft(Write write, UUID actor, UUID basedOn) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into studio_profile(id,revision,status,based_on_profile_id,studio_name,phone,email,address,
                      address_detail,latitude,longitude,business_hours,closed_days,transit_guide,parking_guide,created_by)
                    values(:id,:revision,'DRAFT',:based,:name,:phone,:email,:address,:addressDetail,:latitude,:longitude,
                      cast(:hours as jsonb),:closedDays,:transitGuide,:parkingGuide,:actor)
                    """)
                    .param("id", id).param("revision", maxRevision() + 1).param("based", basedOn)
                    .param("name", write.studioName()).param("phone", write.phone()).param("email", write.email())
                    .param("address", write.address()).param("addressDetail", write.addressDetail())
                    .param("latitude", write.latitude()).param("longitude", write.longitude())
                    .param("hours", mapper.writeValueAsString(write.businessHours()))
                    .param("closedDays", write.closedDays()).param("transitGuide", write.transitGuide())
                    .param("parkingGuide", write.parkingGuide()).param("actor", actor).update();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize studio profile hours", exception);
        }
        return id;
    }

    @Override public int updateDraft(UUID id, Write write) {
        try {
            return jdbc.sql("""
                    update studio_profile set studio_name=:name,phone=:phone,email=:email,address=:address,
                      address_detail=:addressDetail,latitude=:latitude,longitude=:longitude,
                      business_hours=cast(:hours as jsonb),closed_days=:closedDays,transit_guide=:transitGuide,
                      parking_guide=:parkingGuide,version=version+1
                    where id=:id and status='DRAFT' and version=:version
                    """)
                    .param("name", write.studioName()).param("phone", write.phone()).param("email", write.email())
                    .param("address", write.address()).param("addressDetail", write.addressDetail())
                    .param("latitude", write.latitude()).param("longitude", write.longitude())
                    .param("hours", mapper.writeValueAsString(write.businessHours()))
                    .param("closedDays", write.closedDays()).param("transitGuide", write.transitGuide())
                    .param("parkingGuide", write.parkingGuide()).param("id", id).param("version", write.version()).update();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize studio profile hours", exception);
        }
    }

    @Override public void publish(UUID id, long version, UUID actor) {
        jdbc.sql("update studio_profile set status='ARCHIVED' where status='PUBLISHED'").update();
        int changed = jdbc.sql("""
                update studio_profile set status='PUBLISHED',published_by=:actor,
                  published_at=statement_timestamp(),version=version+1
                where id=:id and status='DRAFT' and version=:version
                """).param("actor", actor).param("id", id).param("version", version).update();
        if (changed != 1) throw new StudioProfileException("STUDIO_PROFILE_VERSION_CONFLICT");
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
        if (!hash.equals(row[0])) throw new StudioProfileException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(row[1]) || row[2] == null) throw new StudioProfileException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false, (UUID) row[2]);
    }

    @Override public void complete(String scope, UUID key, UUID id, int responseStatus) {
        jdbc.sql("""
                update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status
                where scope=:scope and idempotency_key=:key
                """).param("id", id).param("status", responseStatus).param("scope", scope).param("key", key).update();
    }

    private static String select() {
        return """
                select id,revision,status,version,studio_name,phone,email,address,address_detail,latitude,longitude,
                  business_hours::text business_hours_json,closed_days,transit_guide,parking_guide,updated_at,published_at
                from studio_profile
                """;
    }

    private static StoredProfile map(ResultSet rs, int row) throws SQLException {
        return new StoredProfile(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status"),
                rs.getLong("version"), rs.getString("studio_name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("address"), rs.getString("address_detail"), rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"), rs.getString("business_hours_json"), rs.getString("closed_days"),
                rs.getString("transit_guide"), rs.getString("parking_guide"), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant());
    }
}
