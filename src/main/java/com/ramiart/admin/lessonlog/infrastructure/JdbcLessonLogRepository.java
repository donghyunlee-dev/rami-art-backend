package com.ramiart.admin.lessonlog.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.lessonlog.application.LessonLogModels.*;
import com.ramiart.admin.lessonlog.application.LessonLogRepository;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLessonLogRepository implements LessonLogRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcLessonLogRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public Optional<Session> findSession(UUID id, boolean lock) {
        return jdbc.sql("""
                select s.id,s.class_group_id,g.name class_group_name,s.status,s.attendance_date,
                       s.starts_at,s.ends_at,s.lesson_plan_item_id
                  from attendance_session s join class_group g on g.id=s.class_group_id
                 where s.id=:id
                """ + (lock ? " for update of s" : ""))
                .param("id", id).query((r, n) -> new Session(r.getObject("id", UUID.class),
                        r.getObject("class_group_id", UUID.class), r.getString("class_group_name"), r.getString("status"),
                        r.getObject("attendance_date", LocalDate.class), instant(r, "starts_at"), instant(r, "ends_at"),
                        r.getObject("lesson_plan_item_id", UUID.class))).optional();
    }

    @Override public List<String> assignedStaff(UUID groupId, LocalDate onDate) {
        return jdbc.sql("""
                select distinct s.display_name from class_staff_assignment a
                  join staff_profile s on s.id=a.staff_profile_id
                 where a.class_group_id=:group and a.effective_from<=:date
                   and coalesce(a.effective_to,'infinity'::date)>=:date and s.status='ACTIVE'
                 order by s.display_name
                """).param("group", groupId).param("date", onDate).query(String.class).list();
    }

    @Override public boolean isOwner(UUID actorId) {
        return jdbc.sql("""
                select exists(select 1 from admin_user_role ur join admin_role r on r.id=ur.admin_role_id
                 where ur.admin_user_id=:actor and r.code='OWNER' and r.active)
                """).param("actor", actorId).query(Boolean.class).single();
    }

    @Override public boolean managesSession(UUID actorId, UUID groupId, LocalDate date) {
        return jdbc.sql("""
                select exists(select 1 from class_staff_assignment a join staff_profile s on s.id=a.staff_profile_id
                 where s.admin_user_id=:actor and s.status='ACTIVE' and a.class_group_id=:group
                   and a.effective_from<=:date and coalesce(a.effective_to,'infinity'::date)>=:date)
                """).param("actor", actorId).param("group", groupId).param("date", date).query(Boolean.class).single();
    }

    @Override public Optional<PlanItem> findPlanItem(UUID id) {
        return jdbc.sql("select id,title,activities::text,materials::text from lesson_plan_item where id=:id")
                .param("id", id).query((r,n) -> new PlanItem(r.getObject("id", UUID.class),r.getString("title"),
                        strings(r,"activities"),strings(r,"materials"))).optional();
    }

    @Override public List<Target> attendanceTargets(UUID sessionId) {
        return jdbc.sql("""
                select t.student_id,t.student_name_snapshot,sa.status,t.display_order
                  from attendance_session_student t left join student_attendance sa
                    on sa.attendance_session_id=t.attendance_session_id and sa.student_id=t.student_id
                 where t.attendance_session_id=:session order by t.display_order,t.student_id
                """).param("session", sessionId).query((r,n) -> new Target(r.getObject("student_id",UUID.class),
                        r.getString("student_name_snapshot"),r.getString("status"),r.getInt("display_order"))).list();
    }

    @Override public List<StoredLog> revisions(UUID sessionId, boolean lock) {
        return jdbc.sql(logSelect() + " where l.attendance_session_id=:session order by l.revision desc" + (lock ? " for update of l" : ""))
                .param("session", sessionId).query(this::storedLog).list();
    }

    @Override public Optional<StoredLog> findLog(UUID logId, boolean lock) {
        return jdbc.sql(logSelect() + " where l.id=:id" + (lock ? " for update of l" : ""))
                .param("id", logId).query(this::storedLog).optional();
    }

    @Override public List<StoredStudent> studentRecords(UUID logId) {
        return jdbc.sql("""
                select r.id,r.student_id,r.attendance_status_snapshot,r.participation,
                       r.progress_note_ciphertext,r.observation_ciphertext,r.absence_note_ciphertext
                  from student_lesson_record r where r.lesson_log_id=:id order by r.student_id
                """).param("id", logId).query((r,n) -> new StoredStudent(r.getObject("id",UUID.class),
                        r.getObject("student_id",UUID.class),r.getString("attendance_status_snapshot"),r.getString("participation"),
                        r.getBytes("progress_note_ciphertext"),r.getBytes("observation_ciphertext"),r.getBytes("absence_note_ciphertext"))).list();
    }

    @Override public List<UUID> artworkAssets(UUID recordId) {
        return jdbc.sql("select asset_id from media_asset_reference where owner_type='STUDENT_LESSON_RECORD' and owner_id=:id and field_name='artwork' order by asset_id")
                .param("id",recordId).query(UUID.class).list();
    }

    @Override public boolean artworkAssetsReady(List<UUID> ids) {
        if (ids.isEmpty()) return true;
        Long ready=jdbc.sql("select count(*) from media_asset where id in (:ids) and status='READY'")
                .param("ids",ids).query(Long.class).single();
        return ready==ids.stream().distinct().count();
    }

    @Override public UUID insertDraft(Session session, PlanItem plan, UUID actorId, String amendReason,
            UUID basedOnLogId, StoredLog source, List<StoredStudent> sourceRecords) {
        int revision=jdbc.sql("select coalesce(max(revision),0)+1 from lesson_log where attendance_session_id=:session")
                .param("session",session.id()).query(Integer.class).single();
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                insert into lesson_log(id,attendance_session_id,revision,status,based_on_log_id,lesson_plan_item_id,
                  actual_title,activities,materials,change_reason,overall_note_ciphertext,amend_reason,created_by)
                values(:id,:session,:revision,'DRAFT',:base,:plan,:title,cast(:activities as jsonb),cast(:materials as jsonb),:change,:note,:reason,:actor)
                """).param("id",id).param("session",session.id()).param("revision",revision).param("base",basedOnLogId)
                .param("plan",plan==null?null:plan.id()).param("title",source==null?(plan==null?"수업 기록":plan.title()):source.actualTitle())
                .param("activities",json(source==null?(plan==null?List.of():plan.activities()):source.activities()))
                .param("materials",json(source==null?(plan==null?List.of():plan.materials()):source.materials()))
                .param("change",source==null?null:source.changeReason())
                .param("note",source==null?null:source.overallNoteCiphertext())
                .param("reason",amendReason).param("actor",actorId).update();
        if (sourceRecords==null) {
            for (Target target:attendanceTargets(session.id())) {
                jdbc.sql("insert into student_lesson_record(id,lesson_log_id,student_id,attendance_status_snapshot) values(:id,:log,:student,:status)")
                        .param("id",UUID.randomUUID()).param("log",id).param("student",target.studentId()).param("status",target.attendanceStatus()).update();
            }
        } else {
            for (StoredStudent old:sourceRecords) {
                UUID recordId=UUID.randomUUID();
                jdbc.sql("""
                        insert into student_lesson_record(id,lesson_log_id,student_id,attendance_status_snapshot,participation,
                          progress_note_ciphertext,observation_ciphertext,absence_note_ciphertext)
                        values(:id,:log,:student,:status,:participation,:progress,:observation,:absence)
                        """).param("id",recordId).param("log",id).param("student",old.studentId()).param("status",old.attendanceStatus())
                        .param("participation",old.participation()).param("progress",old.progressCiphertext())
                        .param("observation",old.observationCiphertext()).param("absence",old.absenceCiphertext()).update();
                insertArtworkReferences(artworkAssets(old.id()),"STUDENT_LESSON_RECORD",recordId);
            }
        }
        return id;
    }

    @Override public int updateDraft(UUID id,long version,SaveWrite write,byte[] note) {
        return jdbc.sql("""
                update lesson_log set lesson_plan_item_id=:plan,actual_title=:title,activities=cast(:activities as jsonb),
                  materials=cast(:materials as jsonb),change_reason=:change,overall_note_ciphertext=:note,version=version+1
                 where id=:id and status='DRAFT' and version=:version
                """).param("plan",write.planItemId()).param("title",write.actualTitle()).param("activities",json(write.activities()))
                .param("materials",json(write.materials())).param("change",write.changeReason()).param("note",note)
                .param("id",id).param("version",version).update();
    }

    @Override public void replaceStudentRecords(UUID logId,List<StudentWriteData> records) {
        List<UUID> oldIds=jdbc.sql("select id from student_lesson_record where lesson_log_id=:id")
                .param("id",logId).query(UUID.class).list();
        for(UUID id:oldIds) removeArtworkReferences(id);
        jdbc.sql("delete from student_lesson_record where lesson_log_id=:id").param("id",logId).update();
        for(StudentWriteData record:records) {
            jdbc.sql("""
                    insert into student_lesson_record(id,lesson_log_id,student_id,attendance_status_snapshot,participation,
                      progress_note_ciphertext,observation_ciphertext,absence_note_ciphertext)
                    values(:id,:log,:student,:status,:participation,:progress,:observation,:absence)
                    """).param("id",record.recordId()).param("log",logId).param("student",record.studentId())
                    .param("status",record.attendanceStatus()).param("participation",record.participation())
                    .param("progress",record.progressCiphertext()).param("observation",record.observationCiphertext())
                    .param("absence",record.absenceCiphertext()).update();
            insertArtworkReferences(record.artworkAssetIds(),"STUDENT_LESSON_RECORD",record.recordId());
        }
    }

    @Override public int finalizeDraft(UUID id,long version,UUID actor,Instant at) {
        return jdbc.sql("update lesson_log set status='FINALIZED',finalized_by=:actor,finalized_at=:at,version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("actor",actor).param("at",at.atOffset(java.time.ZoneOffset.UTC)).param("id",id).param("version",version).update();
    }

    @Override public int amendPrevious(UUID id) {
        return jdbc.sql("update lesson_log set status='AMENDED' where id=:id and status='FINALIZED'")
                .param("id",id).update();
    }

    @Override public boolean claimIdempotency(String scope,UUID key,String hash) {
        return jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update()==1;
    }
    @Override public Optional<String> idempotencyHash(String scope,UUID key) {
        return jdbc.sql("select request_hash from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key).query(String.class).optional();
    }
    @Override public Optional<UUID> idempotencyResource(String scope,UUID key) {
        return jdbc.sql("select resource_id from idempotency_record where scope=:scope and idempotency_key=:key and state='COMPLETED'")
                .param("scope",scope).param("key",key).query(UUID.class).optional();
    }
    @Override public void completeIdempotency(String scope,UUID key,UUID resource,int status) {
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key")
                .param("resource",resource).param("status",status).param("scope",scope).param("key",key).update();
    }
    @Override public String adminDisplayName(UUID actor) {
        return jdbc.sql("select display_name from admin_user where id=:id").param("id",actor).query(String.class).optional().orElse("관리자");
    }
    @Override public void insertArtworkReferences(List<UUID> ids,String ownerType,UUID owner) {
        for(UUID asset:ids) jdbc.sql("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state) values(:asset,:owner_type,:owner,'artwork','PRIVATE') on conflict do nothing")
                .param("asset",asset).param("owner_type",ownerType).param("owner",owner).update();
    }
    @Override public void removeArtworkReferences(UUID owner) {
        jdbc.sql("delete from media_asset_reference where owner_type='STUDENT_LESSON_RECORD' and owner_id=:owner and field_name='artwork'")
                .param("owner",owner).update();
    }

    private String logSelect() { return """
            select l.id,l.attendance_session_id,l.revision,l.status,l.based_on_log_id,l.lesson_plan_item_id,
                   l.actual_title,l.activities::text activities,l.materials::text materials,l.change_reason,
                   l.overall_note_ciphertext,l.amend_reason,l.version,l.created_by,cu.display_name created_by_name,
                   l.created_at,l.finalized_by,fu.display_name finalized_by_name,l.finalized_at
              from lesson_log l join admin_user cu on cu.id=l.created_by left join admin_user fu on fu.id=l.finalized_by
            """; }
    private StoredLog storedLog(ResultSet r,int n) throws SQLException {
        return new StoredLog(r.getObject("id",UUID.class),r.getObject("attendance_session_id",UUID.class),r.getInt("revision"),
                r.getString("status"),r.getObject("based_on_log_id",UUID.class),r.getObject("lesson_plan_item_id",UUID.class),
                r.getString("actual_title"),strings(r,"activities"),strings(r,"materials"),r.getString("change_reason"),
                r.getBytes("overall_note_ciphertext"),r.getString("amend_reason"),r.getLong("version"),
                r.getObject("created_by",UUID.class),r.getString("created_by_name"),instantValue(r,"created_at"),
                r.getObject("finalized_by",UUID.class),r.getString("finalized_by_name"),instantValue(r,"finalized_at"));
    }
    private List<String> strings(ResultSet r,String column) throws SQLException {
        try { return mapper.readValue(r.getString(column),STRINGS); }
        catch(IOException exception) { throw new IllegalStateException("Invalid stored lesson log JSON",exception); }
    }
    private String json(List<String> values) {
        try { return mapper.writeValueAsString(values); }
        catch(IOException exception) { throw new IllegalStateException("Unable to encode lesson log JSON",exception); }
    }
    private static OffsetDateTime instant(ResultSet r,String column) throws SQLException {
        return r.getObject(column,OffsetDateTime.class);
    }
    private static Instant instantValue(ResultSet r,String column) throws SQLException {
        OffsetDateTime value=r.getObject(column,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
}
