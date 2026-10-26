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
        return jdbc.sql(selectProfile() + " where p.status=:status order by p.revision desc limit 1")
                .param("status", status).query(PROFILE).optional();
    }
    @Override public Optional<StoredProfile> findById(UUID id) {
        return jdbc.sql(selectProfile() + " where p.id=:id").param("id", id).query(PROFILE).optional();
    }
    @Override public Optional<StoredProfile> findByIdForUpdate(UUID id) {
        return jdbc.sql(selectProfile() + " where p.id=:id for update of p").param("id", id).query(PROFILE).optional();
    }
    @Override public List<Career> careers(UUID profileId) {
        return jdbc.sql("select id,title,display_order from director_career where director_profile_id=:id order by display_order,id")
                .param("id", profileId).query((rs, n) -> new Career(rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getInt("display_order"))).list();
    }
    @Override public List<StudioImage> studioImages(UUID profileId) {
        return jdbc.sql("""
                select i.id,i.media_asset_id,m.public_path,i.alt_text,i.description,i.display_order
                from director_studio_image i left join media_asset m on m.id=i.media_asset_id
                where i.director_profile_id=:id order by i.display_order,i.id
                """).param("id", profileId).query((rs, n) -> new StudioImage(rs.getObject("id", UUID.class),
                        rs.getObject("media_asset_id", UUID.class), rs.getString("public_path"), rs.getString("alt_text"),
                        rs.getString("description"), rs.getInt("display_order"))).list();
    }
    @Override public boolean imageCurrentlyPublic(UUID assetId) {
        if (assetId == null) return false;
        return jdbc.sql("""
                select exists(select 1 from media_asset where id=:id and status='READY'
                  and public_path is not null and storage_key not like 'private-evidence/%')
                """).param("id", assetId).query(Boolean.class).single();
    }
    @Override public int maxRevision() {
        return jdbc.sql("select coalesce(max(revision),0) from director_profile").query(Integer.class).single();
    }
    @Override public UUID insertDraft(Write write, UUID actor, UUID basedOn) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into director_profile(id,revision,status,based_on_profile_id,name,introduction,studio_introduction,created_by,
                  portrait_media_asset_id,portrait_alt_text)
                values(:id,:revision,'DRAFT',:based,:name,:intro,:studio,:actor,:asset,:alt)
                """).param("id", id).param("revision", maxRevision() + 1).param("based", basedOn)
                .param("name", write.name()).param("intro", write.introduction()).param("studio", write.studioIntroduction())
                .param("actor", actor).param("asset", write.portrait().mediaAssetId()).param("alt", write.portrait().altText()).update();
        return id;
    }
    @Override public int updateDraft(UUID id, Write write) {
        return jdbc.sql("""
                update director_profile set name=:name,introduction=:intro,studio_introduction=:studio,
                  portrait_media_asset_id=:asset,portrait_alt_text=:alt,version=version+1
                where id=:id and status='DRAFT' and version=:version
                """).param("name", write.name()).param("intro", write.introduction()).param("studio", write.studioIntroduction())
                .param("asset", write.portrait().mediaAssetId()).param("alt", write.portrait().altText())
                .param("id", id).param("version", write.version()).update();
    }
    @Override public void replaceCareers(UUID profileId, List<Career> careers) {
        jdbc.sql("delete from director_career where director_profile_id=:id").param("id", profileId).update();
        for (Career career : careers) jdbc.sql("""
                insert into director_career(id,director_profile_id,title,display_order)
                values(:id,:profile,:title,:order)
                """).param("id", career.id()).param("profile", profileId)
                .param("title", career.title()).param("order", career.displayOrder()).update();
    }
    @Override public void replaceStudioImages(UUID profileId, List<StudioImage> images) {
        jdbc.sql("delete from director_studio_image where director_profile_id=:id").param("id", profileId).update();
        for (StudioImage image : images) jdbc.sql("""
                insert into director_studio_image(id,director_profile_id,media_asset_id,alt_text,description,display_order)
                values(:id,:profile,:asset,:alt,:description,:order)
                """).param("id", image.id()).param("profile", profileId).param("asset", image.mediaAssetId())
                .param("alt", image.altText()).param("description", image.description()).param("order", image.displayOrder()).update();
    }
    @Override public void publish(UUID id, long version, UUID actor) {
        jdbc.sql("update director_profile set status='ARCHIVED' where status='PUBLISHED'").update();
        int changed = jdbc.sql("""
                update director_profile set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),version=version+1
                where id=:id and status='DRAFT' and version=:version
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
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
                .param("id", resourceId).param("status", status).param("scope", scope).param("key", key).update();
    }
    private static String selectProfile() {
        return """
                select p.id,p.revision,p.status,p.version,p.name,p.introduction,p.studio_introduction,p.updated_at,p.published_at,
                  p.portrait_media_asset_id,p.portrait_alt_text,m.public_path as portrait_public_path
                from director_profile p left join media_asset m on m.id=p.portrait_media_asset_id
                """;
    }
    private static StoredProfile mapProfile(ResultSet rs, int row) throws SQLException {
        return new StoredProfile(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status"),
                rs.getLong("version"), rs.getString("name"), rs.getString("introduction"), rs.getString("studio_introduction"),
                new Image(rs.getObject("portrait_media_asset_id", UUID.class), rs.getString("portrait_public_path"),
                        rs.getString("portrait_alt_text")), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant());
    }
}
