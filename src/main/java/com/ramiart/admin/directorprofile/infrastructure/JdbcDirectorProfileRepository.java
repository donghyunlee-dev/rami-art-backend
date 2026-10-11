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
        return jdbc.sql("""
                select id,period,title,display_order,hidden from director_career
                where director_profile_id=:profileId order by display_order,id
                """).param("profileId", profileId).query((rs, n) -> new Career(rs.getObject("id", UUID.class),
                rs.getString("period"), rs.getString("title"), rs.getInt("display_order"), rs.getBoolean("hidden"))).list();
    }
    @Override public List<Facility> facilities(UUID profileId) {
        return jdbc.sql("""
                select f.*,m.public_path from director_facility f left join media_asset m on m.id=f.media_asset_id
                where f.director_profile_id=:profileId order by f.display_order,f.id
                """).param("profileId", profileId).query((rs, n) -> new Facility(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("description"), image(rs, "media_asset_id", "public_path", "alt_text", "rights_basis", "includes_student", "student_consent_id"),
                rs.getInt("display_order"), rs.getBoolean("visible"))).list();
    }
    @Override public List<AboutItem> aboutItems(UUID profileId) {
        return jdbc.sql("""
                select id,item_type,icon_code,title,description,display_order,visible from director_about_item
                where director_profile_id=:profileId order by item_type,display_order,id
                """).param("profileId", profileId).query((rs, n) -> new AboutItem(rs.getObject("id", UUID.class),
                rs.getString("item_type"), rs.getString("icon_code"), rs.getString("title"), rs.getString("description"),
                rs.getInt("display_order"), rs.getBoolean("visible"))).list();
    }
    @Override public List<ConsentOption> consentOptions() {
        return jdbc.sql("""
                select c.id, left(s.student_name,80) || ' · ' || c.id::text as label
                from student_consent c join student s on s.id=c.student_id
                where c.policy_type='MEDIA_PUBLICATION' and c.status='ACTIVE'
                  and (c.expires_on is null or c.expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date)
                order by s.student_name,c.consented_at desc
                """).query((rs, n) -> new ConsentOption(rs.getObject("id", UUID.class), rs.getString("label"))).list();
    }
    @Override public boolean imageRightsValid(UUID profileId) {
        return jdbc.sql("""
                select not exists (
                  select 1 from director_profile p left join media_asset m on m.id=p.portrait_media_asset_id
                  left join student_consent c on c.id=p.portrait_student_consent_id
                  where p.id=:id and p.portrait_media_asset_id is not null and (
                    m.status<>'READY' or m.public_path is null or m.storage_key like 'private-evidence/%'
                    or p.portrait_alt_text is null or char_length(btrim(p.portrait_alt_text)) not between 1 and 300
                    or p.portrait_rights_basis is null
                    or (p.portrait_includes_student and (p.portrait_rights_basis<>'STUDENT_CONSENT' or c.id is null
                      or c.policy_type<>'MEDIA_PUBLICATION' or c.status<>'ACTIVE'
                      or c.expires_on is not null and c.expires_on < (statement_timestamp() at time zone 'Asia/Seoul')::date))
                    or (not p.portrait_includes_student and (p.portrait_rights_basis='STUDENT_CONSENT' or c.id is not null))
                  )
                ) and not exists (
                  select 1 from director_facility f left join media_asset m on m.id=f.media_asset_id
                  left join student_consent c on c.id=f.student_consent_id
                  where f.director_profile_id=:id and f.visible and (
                    f.media_asset_id is null or m.status<>'READY' or m.public_path is null or m.storage_key like 'private-evidence/%'
                    or f.alt_text is null or char_length(btrim(f.alt_text)) not between 1 and 300 or f.rights_basis is null
                    or f.includes_student and (f.rights_basis<>'STUDENT_CONSENT' or c.id is null
                      or c.policy_type<>'MEDIA_PUBLICATION' or c.status<>'ACTIVE'
                      or c.expires_on is not null and c.expires_on < (statement_timestamp() at time zone 'Asia/Seoul')::date)
                    or not f.includes_student and (f.rights_basis='STUDENT_CONSENT' or c.id is not null)
                  )
                )
                """).param("id", profileId).query(Boolean.class).single();
    }
    @Override public boolean imageCurrentlyPublic(UUID assetId, boolean includesStudent, UUID consentId, boolean validateConsent) {
        if (assetId == null) return false;
        return jdbc.sql("""
                select exists(select 1 from media_asset m where m.id=:asset and m.status='READY'
                  and m.public_path is not null and m.storage_key not like 'private-evidence/%'
                  and (not :validateConsent or not :includesStudent or exists(select 1 from student_consent c where c.id=:consent
                    and c.policy_type='MEDIA_PUBLICATION' and c.status='ACTIVE'
                    and (c.expires_on is null or c.expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date))))
                """).param("asset", assetId).param("includesStudent", includesStudent).param("consent", consentId)
                .param("validateConsent", validateConsent)
                .query(Boolean.class).single();
    }
    @Override public int maxRevision() {
        return jdbc.sql("select coalesce(max(revision),0) from director_profile").query(Integer.class).single();
    }
    @Override public UUID insertDraft(Write write, UUID actor, UUID basedOn) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into director_profile(id,revision,status,based_on_profile_id,name,title,introduction,created_by,
                  about_eyebrow,about_title,about_description,philosophy_eyebrow,philosophy_title,philosophy_description,
                  facility_eyebrow,facility_title,education_eyebrow,education_title,direction_title,direction_description,
                  portrait_media_asset_id,portrait_alt_text,portrait_rights_basis,portrait_includes_student,portrait_student_consent_id)
                values(:id,:revision,'DRAFT',:based,:name,:title,:introduction,:actor,
                  :aboutEyebrow,:aboutTitle,:aboutDescription,:philosophyEyebrow,:philosophyTitle,:philosophyDescription,
                  :facilityEyebrow,:facilityTitle,:educationEyebrow,:educationTitle,:directionTitle,:directionDescription,
                  :portraitAsset,:portraitAlt,:portraitRights,:portraitStudent,:portraitConsent)
                """).param("id", id).param("revision", maxRevision() + 1).param("based", basedOn)
                .param("name", write.name()).param("title", write.title()).param("introduction", write.introduction())
                .param("actor", actor).param("aboutEyebrow", write.about().eyebrow()).param("aboutTitle", write.about().title())
                .param("aboutDescription", write.about().description()).param("philosophyEyebrow", write.philosophy().eyebrow())
                .param("philosophyTitle", write.philosophy().title()).param("philosophyDescription", write.philosophy().description())
                .param("facilityEyebrow", write.facilitySection().eyebrow()).param("facilityTitle", write.facilitySection().title())
                .param("educationEyebrow", write.educationSection().eyebrow()).param("educationTitle", write.educationSection().title())
                .param("directionTitle", write.direction().title()).param("directionDescription", write.direction().description())
                .param("portraitAsset", write.portrait().mediaAssetId()).param("portraitAlt", write.portrait().altText())
                .param("portraitRights", write.portrait().rightsBasis()).param("portraitStudent", write.portrait().includesStudent())
                .param("portraitConsent", write.portrait().studentConsentId()).update();
        return id;
    }
    @Override public int updateDraft(UUID id, Write write) {
        return jdbc.sql("""
                update director_profile set name=:name,title=:title,introduction=:introduction,
                  about_eyebrow=:aboutEyebrow,about_title=:aboutTitle,about_description=:aboutDescription,
                  philosophy_eyebrow=:philosophyEyebrow,philosophy_title=:philosophyTitle,philosophy_description=:philosophyDescription,
                  facility_eyebrow=:facilityEyebrow,facility_title=:facilityTitle,education_eyebrow=:educationEyebrow,
                  education_title=:educationTitle,direction_title=:directionTitle,direction_description=:directionDescription,
                  portrait_media_asset_id=:portraitAsset,portrait_alt_text=:portraitAlt,portrait_rights_basis=:portraitRights,
                  portrait_includes_student=:portraitStudent,portrait_student_consent_id=:portraitConsent,version=version+1
                where id=:id and status='DRAFT' and version=:version
                """).param("name", write.name()).param("title", write.title()).param("introduction", write.introduction())
                .param("aboutEyebrow", write.about().eyebrow()).param("aboutTitle", write.about().title()).param("aboutDescription", write.about().description())
                .param("philosophyEyebrow", write.philosophy().eyebrow()).param("philosophyTitle", write.philosophy().title()).param("philosophyDescription", write.philosophy().description())
                .param("facilityEyebrow", write.facilitySection().eyebrow()).param("facilityTitle", write.facilitySection().title())
                .param("educationEyebrow", write.educationSection().eyebrow()).param("educationTitle", write.educationSection().title())
                .param("directionTitle", write.direction().title()).param("directionDescription", write.direction().description())
                .param("portraitAsset", write.portrait().mediaAssetId()).param("portraitAlt", write.portrait().altText())
                .param("portraitRights", write.portrait().rightsBasis()).param("portraitStudent", write.portrait().includesStudent())
                .param("portraitConsent", write.portrait().studentConsentId())
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
    @Override public void replaceAbout(UUID profileId, List<Facility> facilities, List<AboutItem> items) {
        jdbc.sql("delete from director_facility where director_profile_id=:id").param("id", profileId).update();
        jdbc.sql("delete from director_about_item where director_profile_id=:id").param("id", profileId).update();
        for (Facility f : facilities) {
            Image image = f.image();
            jdbc.sql("""
                    insert into director_facility(id,director_profile_id,name,description,media_asset_id,alt_text,rights_basis,
                      includes_student,student_consent_id,display_order,visible)
                    values(:id,:profileId,:name,:description,:asset,:alt,:rights,:student,:consent,:order,:visible)
                    """).param("id", f.id()).param("profileId", profileId).param("name", f.name()).param("description", f.description())
                    .param("asset", image.mediaAssetId()).param("alt", image.altText()).param("rights", image.rightsBasis())
                    .param("student", image.includesStudent()).param("consent", image.studentConsentId())
                    .param("order", f.displayOrder()).param("visible", f.visible()).update();
        }
        for (AboutItem item : items) {
            jdbc.sql("""
                    insert into director_about_item(id,director_profile_id,item_type,icon_code,title,description,display_order,visible)
                    values(:id,:profileId,:type,:icon,:title,:description,:order,:visible)
                    """).param("id", item.id()).param("profileId", profileId).param("type", item.itemType())
                    .param("icon", item.iconCode()).param("title", item.title()).param("description", item.description())
                    .param("order", item.displayOrder()).param("visible", item.visible()).update();
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
        return """
                select p.id,p.revision,p.status,p.version,p.name,p.title,p.introduction,p.updated_at,p.published_at,
                  p.about_eyebrow,p.about_title,p.about_description,p.philosophy_eyebrow,p.philosophy_title,p.philosophy_description,
                  p.facility_eyebrow,p.facility_title,p.education_eyebrow,p.education_title,p.direction_title,p.direction_description,
                  p.portrait_media_asset_id,p.portrait_alt_text,p.portrait_rights_basis,p.portrait_includes_student,
                  p.portrait_student_consent_id,m.public_path as portrait_public_path
                from director_profile p left join media_asset m on m.id=p.portrait_media_asset_id
                """;
    }
    private static StoredProfile mapProfile(ResultSet rs, int row) throws SQLException {
        return new StoredProfile(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status"),
                rs.getLong("version"), rs.getString("name"), rs.getString("title"), rs.getString("introduction"),
                new Copy(rs.getString("about_eyebrow"), rs.getString("about_title"), rs.getString("about_description")),
                new Copy(rs.getString("philosophy_eyebrow"), rs.getString("philosophy_title"), rs.getString("philosophy_description")),
                new Copy(rs.getString("facility_eyebrow"), rs.getString("facility_title"), null),
                new Copy(rs.getString("education_eyebrow"), rs.getString("education_title"), null),
                new Copy(null, rs.getString("direction_title"), rs.getString("direction_description")),
                image(rs, "portrait_media_asset_id", "portrait_public_path", "portrait_alt_text", "portrait_rights_basis",
                        "portrait_includes_student", "portrait_student_consent_id"),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("published_at") == null
                        ? null : rs.getTimestamp("published_at").toInstant());
    }
    private static Image image(ResultSet rs, String assetColumn, String urlColumn, String altColumn,
            String rightsColumn, String studentColumn, String consentColumn) throws SQLException {
        return new Image(rs.getObject(assetColumn, UUID.class), rs.getString(urlColumn), rs.getString(altColumn),
                rs.getString(rightsColumn), (Boolean) rs.getObject(studentColumn), rs.getObject(consentColumn, UUID.class));
    }
}
