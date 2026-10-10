package com.ramiart.admin.media.infrastructure;

import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaModels.StoredAsset;
import com.ramiart.admin.media.application.MediaModels.AssetReference;
import com.ramiart.admin.media.application.MediaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMediaRepository implements MediaRepository {
    private final JdbcClient jdbc;

    public JdbcMediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public IdempotencyClaim claim(String scope, UUID key, String requestHash) {
        int inserted = jdbc.sql("""
                        insert into idempotency_record(scope, idempotency_key, request_hash, expires_at)
                        values (:scope, :key, :hash, statement_timestamp() + interval '24 hours')
                        on conflict (scope, idempotency_key) do nothing
                        """)
                .param("scope", scope).param("key", key).param("hash", requestHash).update();
        if (inserted == 1) return new IdempotencyClaim(true, null);
        IdempotencyRow row = jdbc.sql("""
                        select request_hash, state, resource_id from idempotency_record
                         where scope=:scope and idempotency_key=:key
                        """)
                .param("scope", scope).param("key", key)
                .query((rs, ignored) -> new IdempotencyRow(rs.getString(1), rs.getString(2),
                        rs.getObject(3, UUID.class))).single();
        if (!requestHash.equals(row.hash())) throw new MediaException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(row.state()) || row.resourceId() == null) {
            throw new MediaException("IDEMPOTENCY_REQUEST_PROCESSING");
        }
        return new IdempotencyClaim(false, row.resourceId());
    }

    @Override
    public void complete(String scope, UUID key, UUID resourceId, int status) {
        int updated = jdbc.sql("""
                        update idempotency_record set state='COMPLETED', resource_id=:resourceId,
                               response_status=:status
                         where scope=:scope and idempotency_key=:key and state='PROCESSING'
                        """)
                .param("resourceId", resourceId).param("status", status)
                .param("scope", scope).param("key", key).update();
        if (updated != 1) throw new MediaException("MEDIA_STORAGE_FAILED");
    }

    @Override
    public Optional<StoredAsset> find(UUID id) {
        return jdbc.sql("""
                        select id, storage_key, public_path, original_file_name, sha256, mime_type,
                               file_size, width, height, expires_at
                          from media_asset where id=:id and status='READY' and storage_key not like 'private-evidence/%'
                        """)
                .param("id", id).query((rs, ignored) -> new StoredAsset(
                        rs.getObject("id", UUID.class), rs.getString("storage_key"), rs.getString("public_path"),
                        rs.getString("original_file_name"), rs.getString("sha256"), rs.getString("mime_type"),
                        rs.getLong("file_size"), rs.getInt("width"), rs.getInt("height"),
                        rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant())).optional();
    }

    @Override
    public List<AssetReference> references(UUID id) {
        return jdbc.sql("""
                select r.owner_type, r.owner_id revision_id, r.field_name, r.reference_state,
                       case r.owner_type
                         when 'GALLERY_ARTWORK' then (select x.artwork_id from gallery_artwork x where x.id=r.owner_id)
                         when 'BLOG_POST' then (select x.post_id from blog_post x where x.id=r.owner_id)
                         when 'CLASS_PROGRAM' then (select x.course_id from class_program x where x.id=r.owner_id)
                         when 'SITE_BRAND_CONFIG' then r.owner_id
                       end target_id,
                       case r.owner_type
                         when 'GALLERY_ARTWORK' then (select x.title from gallery_artwork x where x.id=r.owner_id)
                         when 'BLOG_POST' then (select x.title from blog_post x where x.id=r.owner_id)
                         when 'CLASS_PROGRAM' then (select x.title from class_program x where x.id=r.owner_id)
                         when 'SITE_BRAND_CONFIG' then (select x.brand_name from site_brand_config x where x.id=r.owner_id)
                       end title,
                       case r.owner_type
                         when 'GALLERY_ARTWORK' then exists(
                           select 1 from gallery_artwork x left join student_consent c on c.id=x.student_consent_id
                            where x.id=r.owner_id and x.status='PUBLISHED' and x.visible
                              and ((x.student_consent_id is not null and c.status='ACTIVE'
                                    and (c.expires_on is null or c.expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date))
                                   or (x.student_consent_id is null and x.consent_exemption_reason is not null)))
                         when 'BLOG_POST' then exists(select 1 from blog_post x where x.id=r.owner_id and x.status='PUBLISHED' and x.visible)
                         when 'CLASS_PROGRAM' then exists(select 1 from class_program x where x.id=r.owner_id and x.status='PUBLISHED' and x.visible)
                         when 'SITE_BRAND_CONFIG' then exists(select 1 from site_brand_config x where x.id=r.owner_id and x.status='PUBLISHED')
                         else false
                       end currently_public,
                       case r.owner_type
                         when 'GALLERY_ARTWORK' then case
                           when exists(select 1 from gallery_artwork x left join student_consent c on c.id=x.student_consent_id
                             where x.id=r.owner_id and x.status='PUBLISHED' and x.visible
                               and ((x.student_consent_id is not null and c.status='ACTIVE'
                                     and (c.expires_on is null or c.expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date))
                                    or (x.student_consent_id is null and x.consent_exemption_reason is not null))) then 'PUBLIC'
                           when exists(select 1 from gallery_artwork x where x.id=r.owner_id and x.status='PUBLISHED' and x.visible) then 'CONSENT_BLOCKED'
                           when exists(select 1 from gallery_artwork x where x.id=r.owner_id and x.status='PUBLISHED') then 'HIDDEN'
                           when exists(select 1 from gallery_artwork x where x.id=r.owner_id and x.status='DRAFT') then 'DRAFT'
                           else 'ARCHIVED' end
                         when 'BLOG_POST' then case
                           when exists(select 1 from blog_post x where x.id=r.owner_id and x.status='PUBLISHED' and x.visible) then 'PUBLIC'
                           when exists(select 1 from blog_post x where x.id=r.owner_id and x.status='PUBLISHED') then 'HIDDEN'
                           when exists(select 1 from blog_post x where x.id=r.owner_id and x.status='DRAFT') then 'DRAFT'
                           else 'ARCHIVED' end
                         when 'CLASS_PROGRAM' then case
                           when exists(select 1 from class_program x where x.id=r.owner_id and x.status='PUBLISHED' and x.visible) then 'PUBLIC'
                           when exists(select 1 from class_program x where x.id=r.owner_id and x.status='PUBLISHED') then 'HIDDEN'
                           when exists(select 1 from class_program x where x.id=r.owner_id and x.status='DRAFT') then 'DRAFT'
                           else 'ARCHIVED' end
                         when 'SITE_BRAND_CONFIG' then case
                           when exists(select 1 from site_brand_config x where x.id=r.owner_id and x.status='PUBLISHED') then 'PUBLIC'
                           when exists(select 1 from site_brand_config x where x.id=r.owner_id and x.status='DRAFT') then 'DRAFT'
                           else 'ARCHIVED' end
                         else 'ARCHIVED'
                       end public_state
                  from media_asset_reference r
                 where r.asset_id=:id and r.owner_type in ('GALLERY_ARTWORK','BLOG_POST','CLASS_PROGRAM','SITE_BRAND_CONFIG')
                 order by currently_public desc, r.owner_type, r.owner_id
                """)
                .param("id", id).query((rs, ignored) -> new AssetReference(
                        rs.getString("owner_type"), rs.getObject("target_id", UUID.class),
                        rs.getObject("revision_id", UUID.class), rs.getString("field_name"),
                        rs.getString("reference_state"), rs.getString("title"), rs.getString("public_state"),
                        rs.getBoolean("currently_public")))
                .list();
    }

    @Override
    public void insert(StoredAsset asset, UUID actorId) {
        jdbc.sql("""
                        insert into media_asset(id, storage_key, public_path, original_file_name, sha256,
                            mime_type, file_size, width, height, status, created_by, expires_at)
                        values (:id, :storageKey, :publicPath, :fileName, :sha256,
                            :mimeType, :fileSize, :width, :height, 'READY', :actorId, :expiresAt)
                        """)
                .param("id", asset.id()).param("storageKey", asset.storageKey())
                .param("publicPath", asset.publicPath()).param("fileName", asset.originalFileName())
                .param("sha256", asset.sha256()).param("mimeType", asset.mimeType())
                .param("fileSize", asset.fileSize()).param("width", asset.width()).param("height", asset.height())
                .param("actorId", actorId).param("expiresAt", Timestamp.from(asset.expiresAt())).update();
    }

    @Override
    public Optional<DeletionCandidate> lockUnreferenced(UUID id) {
        return jdbc.sql("""
                        select a.id, a.storage_key
                          from media_asset a
                         where a.id=:id and a.status='READY' and a.storage_key not like 'private-evidence/%'
                           and not exists(select 1 from media_asset_reference r where r.asset_id=a.id)
                         for update
                        """)
                .param("id", id).query((rs, ignored) -> new DeletionCandidate(
                        rs.getObject("id", UUID.class), rs.getString("storage_key"))).optional();
    }

    @Override
    public int deleteLocked(UUID id) {
        return jdbc.sql("""
                        delete from media_asset a where a.id=:id
                          and not exists(select 1 from media_asset_reference r where r.asset_id=a.id)
                        """).param("id", id).update();
    }

    @Override
    public List<UUID> findExpiredCandidateIds(Instant now, int limit) {
        return jdbc.sql("""
                        select a.id from media_asset a
                         where a.expires_at < :now and a.status='READY' and a.storage_key not like 'private-evidence/%'
                           and not exists(select 1 from media_asset_reference r where r.asset_id=a.id)
                         order by a.expires_at, a.id limit :limit
                        """)
                .param("now", Timestamp.from(now)).param("limit", limit).query(UUID.class).list();
    }

    private record IdempotencyRow(String hash, String state, UUID resourceId) {
    }
}
