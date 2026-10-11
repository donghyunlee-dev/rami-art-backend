package com.ramiart.admin.classprogram.infrastructure;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.classprogram.application.ClassProgramException;
import com.ramiart.admin.classprogram.application.ClassProgramRepository;
import static com.ramiart.admin.classprogram.application.ClassProgramModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClassProgramRepository implements ClassProgramRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public JdbcClassProgramRepository(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Override public List<CoursePrograms> findAll() {
        List<Object[]> courses = jdbc.sql("select id,code,name,age_guide,session_duration_minutes,weekly_sessions,version,active from course order by display_order,id")
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), (Integer) rs.getObject(5), (Integer) rs.getObject(6), rs.getLong(7), rs.getBoolean(8)}).list();
        List<CoursePrograms> result = new ArrayList<>();
        for (Object[] c : courses) {
            UUID id = (UUID)c[0]; Stored published = findCourseStatus(id, "PUBLISHED").orElse(null);
            Stored draft = findCourseStatus(id, "DRAFT").orElse(null);
            result.add(new CoursePrograms(id, (String)c[1], (String)c[2], (String)c[3], (Integer)c[4],
                    (Integer)c[5], (long)c[6], (boolean)c[7], published, draft,
                    new Actions(draft == null, draft != null, draft != null)));
        }
        return List.copyOf(result);
    }
    @Override public Optional<Stored> find(UUID id) { return jdbc.sql(select()+" where p.id=:id").param("id",id).query(this::map).optional(); }
    @Override public Optional<Stored> findCourseStatus(UUID courseId,String status) {
        return jdbc.sql(select()+" where p.course_id=:course and p.status=:status").param("course",courseId).param("status",status).query(this::map).optional();
    }
    @Override public int nextRevision(UUID courseId) { return jdbc.sql("select coalesce(max(revision),0)+1 from class_program where course_id=:id").param("id",courseId).query(Integer.class).single(); }
    @Override public boolean courseExists(UUID id) { return jdbc.sql("select exists(select 1 from course where id=:id)").param("id",id).query(Boolean.class).single(); }
    @Override public UUID createDraft(UUID course,int revision,UUID actor,UUID basedOn) {
        UUID id=UUID.randomUUID();
        if(basedOn==null) jdbc.sql("""
                insert into class_program(id,course_id,revision,status,audience_label,session_duration_minutes,weekly_sessions,source_course_version,created_by)
                select :id,id,:revision,'DRAFT',age_guide,session_duration_minutes,weekly_sessions,version,:actor from course where id=:course
                """)
                .param("id",id).param("course",course).param("revision",revision).param("actor",actor).update();
        else jdbc.sql("""
                insert into class_program(id,course_id,revision,status,visible,based_on_program_id,audience_label,session_duration_minutes,weekly_sessions,source_course_version,title,description,activities,media_asset_id,alt_text,display_order,created_by)
                select :id,p.course_id,:revision,'DRAFT',p.visible,p.id,c.age_guide,c.session_duration_minutes,c.weekly_sessions,c.version,p.title,p.description,p.activities,p.media_asset_id,p.alt_text,p.display_order,:actor
                from class_program p join course c on c.id=p.course_id where p.id=:source and p.status='PUBLISHED'
                """).param("id",id).param("revision",revision).param("actor",actor).param("source",basedOn).update();
        return id;
    }
    @Override public int save(UUID id,ProgramWrite w) {
        try { return jdbc.sql("""
                update class_program p set audience_label=c.age_guide,session_duration_minutes=c.session_duration_minutes,
                    weekly_sessions=c.weekly_sessions,source_course_version=c.version,title=:title,description=:description,
                    activities=cast(:activities as jsonb),media_asset_id=:asset,alt_text=:alt,visible=:visible,
                    display_order=:order,version=p.version+1
                from course c where p.course_id=c.id and p.id=:id and p.status='DRAFT' and p.version=:version
                """).param("title",w.title()).param("description",w.description())
                .param("activities",mapper.writeValueAsString(w.activities())).param("asset",w.mediaAssetId()).param("alt",w.altText())
                .param("visible",w.visible()).param("order",w.displayOrder()).param("id",id).param("version",w.version()).update();
        } catch(JacksonException e) { throw new IllegalStateException("Cannot serialize class program activities",e); }
    }
    @Override public void saveReferences(UUID id,UUID asset) {
        jdbc.sql("delete from media_asset_reference where owner_type='CLASS_PROGRAM' and owner_id=:id and field_name='heroImage' and reference_state='DRAFT'").param("id",id).update();
        if(asset!=null) {
            boolean ready=jdbc.sql("select exists(select 1 from media_asset where id=:id and status='READY')").param("id",asset).query(Boolean.class).single();
            if(!ready) throw new ClassProgramException("CLASS_PROGRAM_INCOMPLETE","mediaAssetId");
            jdbc.sql("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state) values(:asset,'CLASS_PROGRAM',:id,'heroImage','DRAFT') on conflict do nothing")
                    .param("asset",asset).param("id",id).update();
        }
    }
    @Override public void publish(UUID id,long version,UUID actor) {
        jdbc.sql("select id from course where id=(select course_id from class_program where id=:id) for update")
                .param("id",id).query(UUID.class).optional();
        Stored p=find(id).orElseThrow(()->new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND"));
        if(p.version()!=version) throw new ClassProgramException("CLASS_PROGRAM_VERSION_CONFLICT");
        if(p.visible() && !p.courseActive()) throw new ClassProgramException("CLASS_PROGRAM_COURSE_INACTIVE");
        if(!p.sourceMatches()) throw new ClassProgramException("CLASS_PROGRAM_SOURCE_MISMATCH");
        jdbc.sql("update class_program set status='ARCHIVED' where course_id=:course and status='PUBLISHED'").param("course",p.courseId()).update();
        jdbc.sql("update class_program set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("actor",actor).param("id",id).param("version",version).update();
        jdbc.sql("update media_asset_reference set reference_state='PUBLISHED' where owner_type='CLASS_PROGRAM' and owner_id=:id and field_name='heroImage'").param("id",id).update();
    }
    @Override public List<PublicProgram> publicPrograms() { return jdbc.sql("""
            select c.id,c.code,c.name,p.audience_label,p.session_duration_minutes,p.weekly_sessions,
                   p.title,p.description,p.activities::text,a.public_path,p.alt_text,p.display_order
              from class_program p join course c on c.id=p.course_id left join media_asset a on a.id=p.media_asset_id
             where p.status='PUBLISHED' and p.visible=true and c.active=true
               and c.age_guide is not null and c.session_duration_minutes is not null and c.weekly_sessions is not null
               and p.audience_label is not distinct from c.age_guide
               and p.session_duration_minutes is not distinct from c.session_duration_minutes
               and p.weekly_sessions is not distinct from c.weekly_sessions
             order by p.display_order,c.code
            """).query((rs,n)->new PublicProgram(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                    (Integer)rs.getObject(5),(Integer)rs.getObject(6),rs.getString(7),rs.getString(8),activities(rs.getString(9)),
                    rs.getString(10),rs.getString(11),rs.getInt(12))).list(); }
    @Override public Claim claim(String scope,UUID key,String hash) {
        int n=jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,expires_at) values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update(); if(n==1)return new Claim(true,null);
        var row=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update").param("scope",scope).param("key",key)
                .query((rs,i)->new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if(!hash.equals(row[0]))throw new ClassProgramException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(row[1])||row[2]==null)throw new ClassProgramException("IDEMPOTENCY_IN_PROGRESS"); return new Claim(false,(UUID)row[2]);
    }
    @Override public void complete(String scope,UUID key,UUID resource,int status) { jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key").param("resource",resource).param("status",status).param("scope",scope).param("key",key).update(); }
    @Override public String mediaUrl(UUID id) { return jdbc.sql("select public_path from media_asset where id=:id and status='READY'").param("id",id).query(String.class).optional().orElse(null); }
    private String select() { return """
            select p.id,p.course_id,p.revision,p.status,p.visible,p.audience_label,p.title,p.description,p.activities::text activities,
                   p.session_duration_minutes,p.weekly_sessions,p.source_course_version,
                   c.age_guide current_audience_label,c.session_duration_minutes current_session_duration_minutes,
                   c.weekly_sessions current_weekly_sessions,
                   (p.audience_label is not distinct from c.age_guide
                    and p.session_duration_minutes is not distinct from c.session_duration_minutes
                    and p.weekly_sessions is not distinct from c.weekly_sessions) source_matches,
                   p.media_asset_id,a.public_path image_url,p.alt_text,p.display_order,p.version,p.updated_at,p.published_at,
                   c.code course_code,c.name course_name,c.active course_active,a.mime_type,a.file_size,a.width,a.height,a.expires_at
              from class_program p join course c on c.id=p.course_id left join media_asset a on a.id=p.media_asset_id
            """; }
    private Stored map(ResultSet r,int n)throws SQLException{return new Stored(r.getObject("id",UUID.class),r.getObject("course_id",UUID.class),r.getInt("revision"),r.getString("status"),r.getBoolean("visible"),r.getString("audience_label"),r.getString("title"),r.getString("description"),activities(r.getString("activities")),(Integer)r.getObject("session_duration_minutes"),(Integer)r.getObject("weekly_sessions"),r.getLong("source_course_version"),r.getString("current_audience_label"),(Integer)r.getObject("current_session_duration_minutes"),(Integer)r.getObject("current_weekly_sessions"),r.getBoolean("source_matches"),r.getObject("media_asset_id",UUID.class),r.getString("image_url"),r.getString("alt_text"),r.getInt("display_order"),r.getLong("version"),r.getTimestamp("updated_at").toInstant(),r.getTimestamp("published_at")==null?null:r.getTimestamp("published_at").toInstant(),r.getString("course_code"),r.getString("course_name"),r.getBoolean("course_active"),r.getString("mime_type"),r.getLong("file_size"),r.getInt("width"),r.getInt("height"),r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant());}
    private List<String> activities(String json){try{return mapper.readValue(json,mapper.getTypeFactory().constructCollectionType(List.class,String.class));}catch(JacksonException e){throw new IllegalStateException("Invalid stored class program activities",e);}}
}
