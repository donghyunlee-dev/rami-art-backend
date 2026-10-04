package com.ramiart.admin.sitebrand.infrastructure;

import com.ramiart.admin.sitebrand.application.SiteBrandException;
import com.ramiart.admin.sitebrand.application.SiteBrandModels.*;
import com.ramiart.admin.sitebrand.application.SiteBrandRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSiteBrandRepository implements SiteBrandRepository {
    private final JdbcClient jdbc;
    public JdbcSiteBrandRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public Optional<RevisionView> findByStatus(String status) {
        return jdbc.sql(select()+" where b.status=:status order by b.revision desc limit 1").param("status",status).query(this::map).optional();
    }
    @Override public Optional<RevisionView> findById(UUID id) {
        return jdbc.sql(select()+" where b.id=:id").param("id",id).query(this::map).optional();
    }
    @Override public Optional<PublicBrand> findPublic() {
        return jdbc.sql("""
                select b.revision,b.brand_name,b.short_name,l.public_path logo_url,b.logo_alt_text,
                f.public_path favicon_url,s.public_path share_url,b.primary_color,b.accent_color,b.font_preset,
                b.canonical_host,b.default_title,b.default_description,b.instagram_url,b.blog_url,b.published_at
                from site_brand_config b join media_asset l on l.id=b.logo_asset_id
                join media_asset f on f.id=b.favicon_asset_id left join media_asset s on s.id=b.share_asset_id
                where b.status='PUBLISHED' order by b.revision desc limit 1""").query((rs,n)->new PublicBrand(
                rs.getInt("revision"),rs.getString("brand_name"),rs.getString("short_name"),rs.getString("logo_url"),
                rs.getString("logo_alt_text"),rs.getString("favicon_url"),rs.getString("share_url"),rs.getString("primary_color"),
                rs.getString("accent_color"),rs.getString("font_preset"),rs.getString("canonical_host"),rs.getString("default_title"),
                rs.getString("default_description"),rs.getString("instagram_url"),rs.getString("blog_url"),rs.getTimestamp("published_at").toInstant())).optional();
    }
    @Override public int maxRevision() { return jdbc.sql("select coalesce(max(revision),0) from site_brand_config").query(Integer.class).single(); }
    @Override public UUID insertDraft(BrandWrite w, UUID actor, UUID basedOn) {
        if (w.logoAssetId().equals(new UUID(0,0))) {
            UUID logo=jdbc.sql("select id from media_asset where storage_key='seed/generic-brand-logo.png'").query(UUID.class).single();
            UUID favicon=jdbc.sql("select id from media_asset where storage_key='seed/generic-brand-favicon.png'").query(UUID.class).single();
            w=new BrandWrite(w.version(),w.brandName(),w.shortName(),logo,w.logoAltText(),favicon,null,w.primaryColor(),w.accentColor(),w.fontPreset(),w.canonicalHost(),w.defaultTitle(),w.defaultDescription(),null,null);
        }
        UUID id=UUID.randomUUID(); int revision=maxRevision()+1;
        jdbc.sql("""
                insert into site_brand_config(id,revision,status,based_on_id,brand_name,short_name,logo_asset_id,logo_alt_text,
                favicon_asset_id,share_asset_id,primary_color,accent_color,font_preset,canonical_host,default_title,
                default_description,instagram_url,blog_url,created_by) values (:id,:revision,'DRAFT',:based,:name,:short,:logo,:alt,:favicon,:share,:primary,:accent,:font,:host,:title,:description,:instagram,:blog,:actor)""")
                .param("id",id).param("revision",revision).param("based",basedOn).param("name",w.brandName()).param("short",w.shortName())
                .param("logo",w.logoAssetId()).param("alt",w.logoAltText()).param("favicon",w.faviconAssetId()).param("share",w.shareAssetId())
                .param("primary",w.primaryColor()).param("accent",w.accentColor()).param("font",w.fontPreset()).param("host",w.canonicalHost())
                .param("title",w.defaultTitle()).param("description",w.defaultDescription()).param("instagram",w.instagramUrl()).param("blog",w.blogUrl()).param("actor",actor).update();
        return id;
    }
    @Override public int updateDraft(UUID id, BrandWrite w) {
        return jdbc.sql("""
                update site_brand_config set brand_name=:name,short_name=:short,logo_asset_id=:logo,logo_alt_text=:alt,
                favicon_asset_id=:favicon,share_asset_id=:share,primary_color=:primary,accent_color=:accent,font_preset=:font,
                canonical_host=:host,default_title=:title,default_description=:description,instagram_url=:instagram,blog_url=:blog,version=version+1
                where id=:id and status='DRAFT' and version=:version""")
                .param("name",w.brandName()).param("short",w.shortName()).param("logo",w.logoAssetId()).param("alt",w.logoAltText())
                .param("favicon",w.faviconAssetId()).param("share",w.shareAssetId()).param("primary",w.primaryColor()).param("accent",w.accentColor())
                .param("font",w.fontPreset()).param("host",w.canonicalHost()).param("title",w.defaultTitle()).param("description",w.defaultDescription())
                .param("instagram",w.instagramUrl()).param("blog",w.blogUrl()).param("id",id).param("version",w.version()).update();
    }
    @Override public void replaceDraftReferences(UUID id, BrandWrite w) {
        if (w.logoAssetId().equals(new UUID(0,0))) {
            UUID logo=jdbc.sql("select id from media_asset where storage_key='seed/generic-brand-logo.png'").query(UUID.class).single();
            UUID favicon=jdbc.sql("select id from media_asset where storage_key='seed/generic-brand-favicon.png'").query(UUID.class).single();
            w=new BrandWrite(w.version(),w.brandName(),w.shortName(),logo,w.logoAltText(),favicon,null,w.primaryColor(),w.accentColor(),w.fontPreset(),w.canonicalHost(),w.defaultTitle(),w.defaultDescription(),null,null);
        }
        jdbc.sql("delete from media_asset_reference where owner_type='SITE_BRAND_CONFIG' and owner_id=:id and reference_state='DRAFT'").param("id",id).update();
        ref(w.logoAssetId(),id,"logo","DRAFT"); ref(w.faviconAssetId(),id,"favicon","DRAFT"); if(w.shareAssetId()!=null) ref(w.shareAssetId(),id,"shareImage","DRAFT");
    }
    private void ref(UUID asset,UUID id,String field,String state) {
        jdbc.sql("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state) values (:asset,'SITE_BRAND_CONFIG',:id,:field,:state) on conflict do nothing")
                .param("asset",asset).param("id",id).param("field",field).param("state",state).update();
    }
    @Override public void publish(UUID id,long version,UUID actor) {
        jdbc.sql("update site_brand_config set status='ARCHIVED' where status='PUBLISHED'").update();
        int changed=jdbc.sql("update site_brand_config set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("actor",actor).param("id",id).param("version",version).update();
        if(changed!=1) throw new SiteBrandException("SITE_BRAND_VERSION_CONFLICT");
        jdbc.sql("update media_asset_reference set reference_state='PUBLISHED' where owner_type='SITE_BRAND_CONFIG' and owner_id=:id and reference_state='DRAFT'").param("id",id).update();
        jdbc.sql("insert into cache_invalidation_event(id,aggregate_type,aggregate_id,revision) select :event,'SITE_BRAND_CONFIG',id,revision from site_brand_config where id=:id")
                .param("event",UUID.randomUUID()).param("id",id).update();
    }
    @Override public Claim claim(String scope,UUID key,String hash) {
        int inserted=jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,expires_at) values (:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if(inserted==1)return new Claim(true,null);
        var row=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key").param("scope",scope).param("key",key)
                .query((rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if(!hash.equals(row[0]))throw new SiteBrandException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(row[1])||row[2]==null)throw new SiteBrandException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false,(UUID)row[2]);
    }
    @Override public void complete(String scope,UUID key,UUID id,int status) { jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
            .param("id",id).param("status",status).param("scope",scope).param("key",key).update(); }
    @Override public String mediaUrl(UUID id) {
        return jdbc.sql("select public_path from media_asset where id=:id and status='READY'").param("id",id).query(String.class).optional().orElseThrow(()->new SiteBrandException("SITE_BRAND_MEDIA_NOT_READY"));
    }
    @Override public boolean faviconSquare(UUID id) {
        return jdbc.sql("select exists(select 1 from media_asset where id=:id and status='READY' and width=height)")
                .param("id",id).query(Boolean.class).single();
    }
    private String select() { return """
            select b.*, l.public_path logo_url,l.width logo_width,l.height logo_height,
            f.public_path favicon_url,f.width favicon_width,f.height favicon_height,s.public_path share_url,s.width share_width,s.height share_height
            from site_brand_config b join media_asset l on l.id=b.logo_asset_id join media_asset f on f.id=b.favicon_asset_id left join media_asset s on s.id=b.share_asset_id"""; }
    private RevisionView map(ResultSet rs,int n)throws SQLException { return new RevisionView(rs.getObject("id",UUID.class),rs.getInt("revision"),rs.getString("status"),rs.getLong("version"),
            rs.getString("brand_name"),rs.getString("short_name"),rs.getObject("logo_asset_id",UUID.class),rs.getString("logo_alt_text"),rs.getObject("favicon_asset_id",UUID.class),rs.getObject("share_asset_id",UUID.class),
            rs.getString("primary_color"),rs.getString("accent_color"),rs.getString("font_preset"),rs.getString("canonical_host"),rs.getString("default_title"),rs.getString("default_description"),rs.getString("instagram_url"),rs.getString("blog_url"),
            new MediaView(rs.getString("logo_url"),rs.getInt("logo_width"),rs.getInt("logo_height")),new MediaView(rs.getString("favicon_url"),rs.getInt("favicon_width"),rs.getInt("favicon_height")),
            rs.getString("share_url")==null?null:new MediaView(rs.getString("share_url"),rs.getInt("share_width"),rs.getInt("share_height")),rs.getTimestamp("updated_at").toInstant(),rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant()); }
}
