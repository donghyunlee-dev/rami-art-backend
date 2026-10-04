package com.ramiart.admin.blog.infrastructure;

import com.ramiart.admin.blog.application.BlogException;
import com.ramiart.admin.blog.application.BlogModels.Save;
import com.ramiart.admin.blog.application.BlogModels.Write;
import com.ramiart.admin.blog.application.BlogRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBlogRepository implements BlogRepository {
    private static final String SELECT = """
            select b.id,b.post_id,b.revision,b.status,b.visible,b.based_on_revision_id,b.title,b.summary,b.category,
                   b.content,b.media_asset_id,m.public_path,m.status media_status,b.alt_text,b.author_id,
                   coalesce(u.display_name,'삭제된 관리자') author_name,coalesce(u.status='ACTIVE',false) author_active,
                   b.published_by,b.published_at,b.version,b.updated_at
              from blog_post b left join media_asset m on m.id=b.media_asset_id
              left join admin_user u on u.id=b.author_id
            """;
    private final JdbcClient jdbc;
    public JdbcBlogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Revision> findRevision(UUID id) {
        return jdbc.sql(SELECT + " where b.id=:id").param("id", id).query(this::map).optional();
    }
    @Override public Optional<Revision> findByPostAndStatus(UUID postId, String status) {
        return jdbc.sql(SELECT + " where b.post_id=:postId and b.status=:status")
                .param("postId", postId).param("status", status).query(this::map).optional();
    }
    @Override public List<Revision> listAdmin(String keyword, List<String> categories, List<String> states,
            String sort, int page, int size) {
        String order = switch (sort) {
            case "TITLE_ASC" -> "b.title asc nulls last,b.post_id";
            case "PUBLISHED_DESC" -> "(select x.published_at from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED') desc nulls last,b.post_id";
            default -> "b.updated_at desc,b.post_id";
        };
        String sql = SELECT + """
                 where b.status in ('DRAFT','PUBLISHED')
                   and b.id=coalesce((select x.id from blog_post x where x.post_id=b.post_id and x.status='DRAFT'),
                                     (select x.id from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED'))
                   and (:keyword is null or coalesce(b.title,(select x.title from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED')) ilike '%' || :keyword || '%')
                   and (cardinality(cast(:categories as text[]))=0 or coalesce(b.category,(select x.category from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED'))=any(cast(:categories as text[])))
                   and (cardinality(cast(:states as text[]))=0
                     or ('DRAFT_ONLY'=any(cast(:states as text[])) and b.status='DRAFT' and not exists(select 1 from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED'))
                     or ('PUBLISHED_VISIBLE'=any(cast(:states as text[])) and exists(select 1 from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED' and x.visible))
                     or ('PUBLISHED_HIDDEN'=any(cast(:states as text[])) and exists(select 1 from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED' and not x.visible))
                     or ('HAS_DRAFT'=any(cast(:states as text[])) and exists(select 1 from blog_post x where x.post_id=b.post_id and x.status='DRAFT')))
                """;
        return jdbc.sql(sql + " order by " + order + " limit :size offset :offset")
                .param("keyword", keyword).param("categories", categories.toArray(String[]::new))
                .param("states", states.toArray(String[]::new)).param("size", size).param("offset", page * size)
                .query(this::map).list();
    }
    @Override public long countAdmin(String keyword, List<String> categories, List<String> states) {
        return jdbc.sql("""
                select count(distinct b.post_id) from blog_post b
                  left join lateral (select * from blog_post x where x.post_id=b.post_id and x.status='DRAFT') d on true
                  left join lateral (select * from blog_post x where x.post_id=b.post_id and x.status='PUBLISHED') p on true
                 where b.status in ('DRAFT','PUBLISHED')
                   and (:keyword is null or coalesce(d.title,p.title) ilike '%' || :keyword || '%')
                   and (cardinality(cast(:categories as text[]))=0 or coalesce(d.category,p.category)=any(cast(:categories as text[])))
                   and (cardinality(cast(:states as text[]))=0
                     or ('DRAFT_ONLY'=any(cast(:states as text[])) and d.id is not null and p.id is null)
                     or ('PUBLISHED_VISIBLE'=any(cast(:states as text[])) and p.visible)
                     or ('PUBLISHED_HIDDEN'=any(cast(:states as text[])) and p.id is not null and not p.visible)
                     or ('HAS_DRAFT'=any(cast(:states as text[])) and d.id is not null))
                """).param("keyword", keyword).param("categories", categories.toArray(String[]::new))
                .param("states", states.toArray(String[]::new)).query(Long.class).single();
    }
    @Override public int nextRevision(UUID postId) {
        return jdbc.sql("select coalesce(max(revision),0)+1 from blog_post where post_id=:id")
                .param("id",postId).query(Integer.class).single();
    }
    @Override public List<Revision> listPublic(String category, String keyword, int page, int size) {
        return jdbc.sql(SELECT + " where b.status='PUBLISHED' and b.visible=true "
                + "and (cast(:category as text) is null or b.category=cast(:category as text)) and (cast(:keyword as text) is null or b.title ilike '%'||cast(:keyword as text)||'%') "
                + "order by b.published_at desc,b.post_id desc limit :size offset :offset")
                .param("category", category).param("keyword", keyword).param("size", size)
                .param("offset", (page - 1) * size).query(this::map).list();
    }
    @Override public long countPublic(String category, String keyword) {
        return jdbc.sql("select count(*) from blog_post where status='PUBLISHED' and visible=true "
                + "and (cast(:category as text) is null or category=cast(:category as text)) and (cast(:keyword as text) is null or title ilike '%'||cast(:keyword as text)||'%')")
                .param("category", category).param("keyword", keyword).query(Long.class).single();
    }
    @Override public Revision insertDraft(UUID postId, UUID id, int revision, UUID basedOn, Write b, UUID actor) {
        jdbc.sql("""
                insert into blog_post(id,post_id,revision,status,visible,based_on_revision_id,title,summary,category,
                   content,media_asset_id,alt_text,author_id,created_by)
                values(:id,:postId,:revision,'DRAFT',:visible,:basedOn,:title,:summary,:category,:content,:mediaId,:alt,:actor,:actor)
                """).param("id",id).param("postId",postId).param("revision",revision).param("visible",Boolean.TRUE.equals(b.visible()))
                .param("basedOn",basedOn).param("title",emptyToNull(b.title())).param("summary",emptyToNull(b.summary()))
                .param("category",b.category()).param("content",b.content()).param("mediaId",b.mediaAssetId())
                .param("alt",emptyToNull(b.altText())).param("actor",actor).update();
        return findRevision(id).orElseThrow();
    }
    @Override public int saveDraft(UUID draftId, Save b) {
        return jdbc.sql("""
                update blog_post set title=:title,summary=:summary,category=:category,content=:content,
                    media_asset_id=:mediaId,alt_text=:alt,visible=:visible,version=version+1,updated_at=statement_timestamp()
                 where id=:id and status='DRAFT' and version=:version
                """).param("title",emptyToNull(b.title())).param("summary",emptyToNull(b.summary())).param("category",b.category())
                .param("content",b.content()).param("mediaId",b.mediaAssetId()).param("alt",emptyToNull(b.altText()))
                .param("visible",b.visible()).param("id",draftId).param("version",b.version()).update();
    }
    @Override public void archivePublished(UUID postId) {
        jdbc.sql("update blog_post set status='ARCHIVED',updated_at=statement_timestamp() where post_id=:id and status='PUBLISHED'")
                .param("id",postId).update();
    }
    @Override public void publishDraft(UUID draftId, UUID actor) {
        int updated = jdbc.sql("update blog_post set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),updated_at=statement_timestamp() where id=:id and status='DRAFT'")
                .param("actor",actor).param("id",draftId).update();
        if (updated != 1) throw new BlogException("BLOG_POST_REVISION_NOT_FOUND");
    }
    @Override public void updateDraftReference(UUID draftId, UUID oldAssetId, UUID newAssetId) {
        if (oldAssetId != null) jdbc.sql("delete from media_asset_reference where asset_id=:asset and owner_type='BLOG_POST' and owner_id=:id and field_name='thumbnailImage' and reference_state='DRAFT'")
                .param("asset",oldAssetId).param("id",draftId).update();
        if (newAssetId != null) jdbc.sql("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state) values(:asset,'BLOG_POST',:id,'thumbnailImage','DRAFT') on conflict do nothing")
                .param("asset",newAssetId).param("id",draftId).update();
    }
    @Override public void publishReference(UUID draftId) {
        jdbc.sql("update media_asset_reference set reference_state='PUBLISHED' where owner_type='BLOG_POST' and owner_id=:id and field_name='thumbnailImage'")
                .param("id",draftId).update();
    }
    @Override public boolean mediaReady(UUID assetId) {
        return jdbc.sql("select exists(select 1 from media_asset where id=:id and status='READY')")
                .param("id",assetId).query(Boolean.class).single();
    }
    @Override public Idempotency claim(String scope, UUID key, String hash) {
        int inserted = jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,expires_at) values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if (inserted == 1) return new Idempotency(true,null);
        var row = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key).query((rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if (!hash.equals(row[0])) throw new BlogException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(row[1]) || row[2] == null) throw new BlogException("IDEMPOTENCY_REQUEST_PROCESSING");
        return new Idempotency(false,(UUID)row[2]);
    }
    @Override public void complete(String scope, UUID key, UUID resource, int status) {
        if (jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key and state='PROCESSING'")
                .param("resource",resource).param("status",status).param("scope",scope).param("key",key).update()!=1)
            throw new BlogException("BLOG_POST_SAVE_FAILED");
    }
    private Revision map(ResultSet rs, int row) throws SQLException {
        return new Revision(rs.getObject("id",UUID.class),rs.getObject("post_id",UUID.class),rs.getInt("revision"),rs.getString("status"),
                rs.getBoolean("visible"),rs.getObject("based_on_revision_id",UUID.class),rs.getString("title"),rs.getString("summary"),
                rs.getString("category"),rs.getString("content"),rs.getObject("media_asset_id",UUID.class),rs.getString("public_path"),
                rs.getString("media_status"),rs.getString("alt_text"),rs.getObject("author_id",UUID.class),rs.getString("author_name"),
                rs.getBoolean("author_active"),rs.getObject("published_by",UUID.class),rs.getObject("published_at",java.time.OffsetDateTime.class),
                rs.getLong("version"),rs.getObject("updated_at",java.time.OffsetDateTime.class));
    }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
