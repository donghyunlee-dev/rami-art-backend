package com.ramiart.admin.homecontent.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.homecontent.application.HomePageContentException;
import com.ramiart.admin.homecontent.application.HomePageContentRepository;
import static com.ramiart.admin.homecontent.application.HomePageContentModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcHomePageContentRepository implements HomePageContentRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcHomePageContentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    @Override public Optional<RevisionView> findByStatus(String status) {
        return jdbc.sql(selectRevision() + " where h.status=:status").param("status", status).query(this::revision).optional();
    }
    @Override public Optional<RevisionView> findById(UUID id) {
        return jdbc.sql(selectRevision() + " where h.id=:id").param("id", id).query(this::revision).optional();
    }
    @Override public List<RevisionSummary> history() {
        return jdbc.sql("select id,revision,status,published_at from home_page_content order by revision desc limit 50")
                .query((rs, row) -> new RevisionSummary(rs.getObject("id", UUID.class), rs.getInt("revision"), rs.getString("status"), instant(rs,"published_at"))).list();
    }
    @Override public int maxRevision() { return jdbc.sql("select coalesce(max(revision),0) from home_page_content").query(Integer.class).single(); }

    @Override public UUID insertDraft(HomePageWrite write, UUID actor, UUID basedOnId) {
        UUID id = UUID.randomUUID();
        Hero hero = write.hero();
        jdbc.sql("""
                insert into home_page_content(id,revision,status,based_on_revision_id,hero_title,hero_description,
                  hero_media_asset_id,hero_alt_text,hero_cta_label,hero_cta_target,created_by)
                values(:id,:revision,'DRAFT',:based,:title,:description,:media,:alt,:label,:target,:actor)
                """)
                .param("id",id).param("revision",maxRevision()+1).param("based",basedOnId)
                .param("title",hero.title()).param("description",hero.description()).param("media",hero.mediaAssetId())
                .param("alt",hero.altText()).param("label",hero.ctaLabel()).param("target",hero.ctaTarget()).param("actor",actor).update();
        return id;
    }

    @Override public int updateDraft(UUID id, HomePageWrite write) {
        Hero hero = write.hero();
        return jdbc.sql("""
                update home_page_content set hero_title=:title,hero_description=:description,hero_media_asset_id=:media,
                  hero_alt_text=:alt,hero_cta_label=:label,hero_cta_target=:target,version=version+1
                where id=:id and status='DRAFT' and version=:version
                """)
                .param("title",hero.title()).param("description",hero.description()).param("media",hero.mediaAssetId())
                .param("alt",hero.altText()).param("label",hero.ctaLabel()).param("target",hero.ctaTarget())
                .param("id",id).param("version",write.version()).update();
    }

    @Override public void replaceChildren(UUID id, HomePageWrite write) {
        jdbc.sql("delete from home_page_strength where content_id=:id").param("id",id).update();
        jdbc.sql("delete from home_page_section where content_id=:id").param("id",id).update();
        jdbc.sql("delete from home_page_reference where content_id=:id").param("id",id).update();
        for (StrengthWrite strength : write.strengths()) {
            jdbc.sql("insert into home_page_strength(content_id,display_order,icon_code,title,description) values(:id,:order,:icon,:title,:description)")
                    .param("id",id).param("order",strength.displayOrder()).param("icon",strength.iconCode())
                    .param("title",strength.title()).param("description",strength.description()).update();
        }
        for (SectionWrite section : write.sections()) {
            jdbc.sql("insert into home_page_section(content_id,section_key,visible,display_order) values(:id,:key,:visible,:order)")
                    .param("id",id).param("key",section.sectionKey()).param("visible",section.visible()).param("order",section.displayOrder()).update();
        }
        insertReferences(id,"COURSE",write.references().courses());
        insertReferences(id,"ARTWORK",write.references().artworks());
        insertReferences(id,"BLOG_POST",write.references().posts());
    }

    private void insertReferences(UUID id, String type, List<UUID> targets) {
        for (int i = 0; i < targets.size(); i++) {
            jdbc.sql("insert into home_page_reference(content_id,reference_type,target_id,display_order) values(:id,:type,:target,:order)")
                    .param("id",id).param("type",type).param("target",targets.get(i)).param("order",i).update();
        }
    }

    @Override public void publish(UUID id, long version, UUID actor) {
        jdbc.sql("update home_page_content set status='ARCHIVED' where status='PUBLISHED'").update();
        int changed = jdbc.sql("update home_page_content set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("actor",actor).param("id",id).param("version",version).update();
        if (changed != 1) throw new HomePageContentException("HOME_CONTENT_VERSION_CONFLICT");
    }

    @Override public List<ValidationError> validateReferences(RevisionView revision) {
        List<ValidationError> errors = new ArrayList<>();
        if (revision.hero().mediaAssetId() == null || !jdbc.sql("select exists(select 1 from media_asset where id=:id and status='READY' and public_path is not null and storage_key not like 'private-evidence/%')")
                .param("id",revision.hero().mediaAssetId()).query(Boolean.class).single()) {
            errors.add(new ValidationError("HERO_MEDIA", revision.hero().mediaAssetId(), "HOME_CONTENT_MEDIA_NOT_READY", "Hero 이미지가 준비되지 않았습니다."));
        }
        Options available = options();
        validateType(errors,"COURSE",revision.references().courses(),available.courses());
        validateType(errors,"ARTWORK",revision.references().artworks(),available.artworks());
        validateType(errors,"BLOG_POST",revision.references().posts(),available.posts());
        return List.copyOf(errors);
    }

    private static void validateType(List<ValidationError> errors, String type, List<HomeReference> references, List<Option> options) {
        for (HomeReference reference : references) {
            boolean found = options.stream().anyMatch(option -> option.id().equals(reference.id()));
            if (!found) errors.add(new ValidationError(type, reference.id(), "HOME_CONTENT_REFERENCE_NOT_PUBLIC", "선택한 원본이 공개 상태가 아닙니다."));
        }
    }

    @Override public Options options() {
        List<Option> courses = jdbc.sql("""
                select c.id,c.name,p.revision from course c join class_program p on p.course_id=c.id
                join media_asset a on a.id=p.media_asset_id and a.status='READY' and a.public_path is not null
                where c.active and p.status='PUBLISHED' and p.visible
                  and p.audience_label is not distinct from c.age_guide
                  and p.session_duration_minutes is not distinct from c.session_duration_minutes
                  and p.weekly_sessions is not distinct from c.weekly_sessions
                  and c.age_guide is not null and c.session_duration_minutes is not null and c.weekly_sessions is not null
                order by p.display_order,c.id
                """).query((rs,row)->new Option(rs.getObject("id",UUID.class),rs.getString("name"),rs.getInt("revision"))).list();
        List<Option> artworks = jdbc.sql("""
                select g.artwork_id,g.title,g.revision from gallery_artwork g
                join media_asset a on a.id=g.media_asset_id and a.status='READY' and a.public_path is not null
                left join student_consent sc on sc.id=g.student_consent_id
                where g.status='PUBLISHED' and g.visible and
                  ((g.student_consent_id is not null and sc.status='ACTIVE' and (sc.expires_on is null or sc.expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date))
                   or (g.student_consent_id is null and g.consent_exemption_reason is not null))
                order by g.featured desc,g.featured_order asc nulls last,g.published_at desc,g.artwork_id desc
                """).query((rs,row)->new Option(rs.getObject("artwork_id",UUID.class),rs.getString("title"),rs.getInt("revision"))).list();
        List<Option> posts = jdbc.sql("""
                select b.post_id,b.title,b.revision from blog_post b
                join media_asset a on a.id=b.media_asset_id and a.status='READY' and a.public_path is not null
                where b.status='PUBLISHED' and b.visible order by b.published_at desc,b.post_id desc
                """).query((rs,row)->new Option(rs.getObject("post_id",UUID.class),rs.getString("title"),rs.getInt("revision"))).list();
        return new Options(courses,artworks,posts);
    }

    @Override public Optional<PublicView> findPublic() {
        Optional<RevisionView> found = findByStatus("PUBLISHED");
        if (found.isEmpty()) return Optional.empty();
        RevisionView page = found.get();
        List<PublicSection> sections = page.sections().stream().filter(Section::visible)
                .map(s -> new PublicSection(s.sectionKey(),s.displayOrder())).sorted((a,b)->Integer.compare(a.displayOrder(),b.displayOrder())).toList();
        List<PublicStrength> strengths = page.strengths().stream().map(s->new PublicStrength(s.iconCode(),s.title(),s.description(),s.displayOrder())).toList();
        List<PublicCourse> courses = publicCourses(page.references().courses());
        List<PublicArtwork> artworks = publicArtworks(page.references().artworks());
        List<PublicPost> posts = publicPosts(page.references().posts());
        Hero sourceHero = page.hero();
        String href = switch (sourceHero.ctaTarget()) {
            case "CONTACT_INQUIRY" -> "/contact";
            case "CLASSES" -> "/classes";
            case "GALLERY_WORKS" -> "/gallery";
            case "BLOG" -> "/blog";
            default -> throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "hero.ctaTarget");
        };
        PublicHero hero = new PublicHero(sourceHero.title(),sourceHero.description(),sourceHero.imageUrl(),sourceHero.altText(),sourceHero.ctaLabel(),href);
        return Optional.of(new PublicView(page.revision(),hero,strengths,sections,courses,artworks,posts,page.publishedAt()));
    }

    private List<PublicCourse> publicCourses(List<HomeReference> refs) {
        List<PublicCourse> values = new ArrayList<>();
        for (HomeReference ref : refs) jdbc.sql("""
                select c.id course_id,p.revision,c.code course_code,p.audience_label,p.session_duration_minutes,p.weekly_sessions,
                  p.title,p.description,p.activities,
                  a.public_path image_url,p.alt_text,p.display_order
                from course c join class_program p on p.course_id=c.id join media_asset a on a.id=p.media_asset_id
                where c.id=:id and c.active and p.status='PUBLISHED' and p.visible and a.status='READY' and a.public_path is not null
                  and p.audience_label is not distinct from c.age_guide
                  and p.session_duration_minutes is not distinct from c.session_duration_minutes
                  and p.weekly_sessions is not distinct from c.weekly_sessions
                  and c.age_guide is not null and c.session_duration_minutes is not null and c.weekly_sessions is not null
                """).param("id",ref.id()).query((rs,row)->new PublicCourse(rs.getObject("course_id",UUID.class),rs.getInt("revision"),
                    rs.getString("course_code"),rs.getString("audience_label"),
                    (Integer)rs.getObject("session_duration_minutes"),(Integer)rs.getObject("weekly_sessions"),
                    rs.getString("title"),rs.getString("description"),readActivities(rs),rs.getString("image_url"),
                    rs.getString("alt_text"),rs.getInt("display_order")))
                .optional().ifPresent(values::add);
        return values;
    }

    private List<PublicArtwork> publicArtworks(List<HomeReference> refs) {
        List<PublicArtwork> values = new ArrayList<>();
        for (HomeReference ref : refs) jdbc.sql("""
                select g.artwork_id,g.revision,c.code course_code,g.audience_label,g.title,g.medium,g.description,
                  a.public_path image_url,g.alt_text from gallery_artwork g join course c on c.id=g.course_id
                join media_asset a on a.id=g.media_asset_id left join student_consent sc on sc.id=g.student_consent_id
                where g.artwork_id=:id and g.status='PUBLISHED' and g.visible and a.status='READY' and a.public_path is not null and
                  ((g.student_consent_id is not null and sc.status='ACTIVE' and (sc.expires_on is null or sc.expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date))
                   or (g.student_consent_id is null and g.consent_exemption_reason is not null))
                """).param("id",ref.id()).query((rs,row)->new PublicArtwork(rs.getObject("artwork_id",UUID.class),rs.getInt("revision"),rs.getString("course_code"),
                    rs.getString("audience_label"),rs.getString("title"),rs.getString("medium"),rs.getString("description"),rs.getString("image_url"),rs.getString("alt_text"),ref.displayOrder()))
                .optional().ifPresent(values::add);
        return values;
    }

    private List<PublicPost> publicPosts(List<HomeReference> refs) {
        List<PublicPost> values = new ArrayList<>();
        for (HomeReference ref : refs) jdbc.sql("""
                select b.post_id,b.revision,b.title,b.summary,b.category,a.public_path image_url,b.alt_text,b.published_at
                from blog_post b join media_asset a on a.id=b.media_asset_id
                where b.post_id=:id and b.status='PUBLISHED' and b.visible and a.status='READY' and a.public_path is not null
                """).param("id",ref.id()).query((rs,row)->new PublicPost(rs.getObject("post_id",UUID.class),rs.getInt("revision"),
                    rs.getString("title"),rs.getString("summary"),rs.getString("category"),rs.getString("image_url"),rs.getString("alt_text"),
                    instant(rs,"published_at"),ref.displayOrder())).optional().ifPresent(values::add);
        return values;
    }

    @Override public Claim claim(String scope, UUID key, String hash) {
        int inserted = jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,expires_at) values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if (inserted == 1) return new Claim(true,null);
        var existing = jdbc.sql("select request_hash,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key).query((rs,row)->new ClaimRow(rs.getString("request_hash"),rs.getObject("resource_id",UUID.class))).optional();
        if (existing.isEmpty() || !hash.equals(existing.get().hash())) throw new HomePageContentException("IDEMPOTENCY_KEY_REUSED");
        if (existing.get().resourceId() == null) throw new HomePageContentException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false,existing.get().resourceId());
    }
    private record ClaimRow(String hash, UUID resourceId) {}
    @Override public void complete(String scope, UUID key, UUID resourceId, int status) {
        jdbc.sql("update idempotency_record set resource_id=:id,response_status=:status,completed_at=statement_timestamp() where scope=:scope and idempotency_key=:key")
                .param("id",resourceId).param("status",status).param("scope",scope).param("key",key).update();
    }

    private RevisionView revision(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id",UUID.class);
        Hero hero = new Hero(rs.getString("hero_title"),rs.getString("hero_description"),rs.getObject("hero_media_asset_id",UUID.class),
                rs.getString("hero_url"),rs.getString("hero_alt_text"),rs.getString("hero_cta_label"),rs.getString("hero_cta_target"));
        List<Strength> strengths = jdbc.sql("select icon_code,title,description,display_order from home_page_strength where content_id=:id order by display_order")
                .param("id",id).query((r,n)->new Strength(r.getString("icon_code"),r.getString("title"),r.getString("description"),r.getInt("display_order"))).list();
        List<Section> sections = jdbc.sql("select section_key,visible,display_order from home_page_section where content_id=:id order by display_order")
                .param("id",id).query((r,n)->new Section(r.getString("section_key"),r.getBoolean("visible"),r.getInt("display_order"))).list();
        References refs = new References(references(id,"COURSE"),references(id,"ARTWORK"),references(id,"BLOG_POST"));
        return new RevisionView(id,rs.getInt("revision"),rs.getString("status"),rs.getLong("version"),hero,strengths,sections,refs,
                instant(rs,"updated_at"),instant(rs,"published_at"));
    }
    private List<HomeReference> references(UUID id, String type) {
        Options options = options();
        List<Option> candidates = switch (type) { case "COURSE" -> options.courses(); case "ARTWORK" -> options.artworks(); default -> options.posts(); };
        Map<UUID,Option> byId = new LinkedHashMap<>(); candidates.forEach(o->byId.put(o.id(),o));
        return jdbc.sql("select target_id,display_order from home_page_reference where content_id=:id and reference_type=:type order by display_order")
                .param("id",id).param("type",type).query((rs,row)->{
                    UUID target = rs.getObject("target_id",UUID.class); int order=rs.getInt("display_order"); Option option=byId.get(target);
                    return new HomeReference(target,option==null?"공개할 수 없는 콘텐츠":option.label(),option==null?0:option.revision(),order);
                }).list();
    }
    private static String selectRevision() {
        return "select h.*,a.public_path hero_url from home_page_content h left join media_asset a on a.id=h.hero_media_asset_id";
    }
    private List<String> readActivities(ResultSet rs) {
        try { return mapper.readValue(rs.getString("activities"), new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid published class program activities",error); }
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
}
