package com.ramiart.admin.attendance.infrastructure;

import com.ramiart.admin.attendance.application.AttendanceRepository;
import com.ramiart.admin.attendance.application.AttendanceModels.AttendanceWrite;
import com.ramiart.admin.attendance.application.AttendanceModels.Summary;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceRepository implements AttendanceRepository {
    private final JdbcClient jdbc;
    public JdbcAttendanceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public List<StudentRow> findByDate(LocalDate date) {
        return rows("s.attendance_date=:date", statement -> statement.param("date", date));
    }
    @Override public Optional<List<StudentRow>> findBySessionId(UUID sessionId) {
        List<StudentRow> rows = rows("s.id=:sessionId", statement -> statement.param("sessionId", sessionId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
    }
    private List<StudentRow> rows(String filter, java.util.function.Function<org.springframework.jdbc.core.simple.JdbcClient.StatementSpec, org.springframework.jdbc.core.simple.JdbcClient.StatementSpec> bind) {
        var statement = jdbc.sql("""
            select s.id session_id,s.schedule_slot_id,s.class_group_id,s.room_code_snapshot,s.attendance_date,s.class_name_snapshot,s.starts_at,s.ends_at,s.status session_status,s.version session_version,
                   t.display_order,t.student_id,t.student_name_snapshot,
                   a.status attendance_status,a.check_in_time,a.reason,coalesce(a.makeup_eligible,false) makeup_eligible,
                   coalesce(a.version,0) attendance_version,a.updated_at,s.closed_by,closed_admin.display_name closed_by_name,
                   s.closed_at,s.present_count,s.late_count,s.absent_count,s.excused_count,group_data.makeup_valid_days,
                   t.makeup_case_id,makeup.status makeup_status
              from attendance_session s
              left join attendance_session_student t on t.attendance_session_id=s.id
              left join student_attendance a on a.attendance_session_id=t.attendance_session_id and a.student_id=t.student_id
              join class_group group_data on group_data.id=s.class_group_id
              left join admin_user closed_admin on closed_admin.id=s.closed_by
              left join makeup_case makeup on makeup.id=t.makeup_case_id
             where """ + " " + filter + " order by s.starts_at,s.id,t.display_order,t.student_id");
        return bind.apply(statement).query((r, n) -> new StudentRow(
                r.getObject("session_id", java.util.UUID.class), r.getObject("schedule_slot_id", java.util.UUID.class),
                r.getObject("class_group_id", UUID.class), r.getString("room_code_snapshot"), r.getObject("attendance_date", LocalDate.class),
                r.getString("class_name_snapshot"), r.getObject("starts_at", java.time.OffsetDateTime.class), r.getObject("ends_at", java.time.OffsetDateTime.class),
                r.getString("session_status"), r.getLong("session_version"), r.getInt("display_order"), r.getObject("student_id", java.util.UUID.class),
                r.getString("student_name_snapshot"), r.getString("attendance_status"), r.getObject("check_in_time", java.time.LocalTime.class), r.getString("reason"),
                r.getBoolean("makeup_eligible"), r.getLong("attendance_version"), r.getObject("updated_at", java.time.OffsetDateTime.class),
                r.getObject("closed_by", UUID.class), r.getString("closed_by_name"), r.getObject("closed_at", OffsetDateTime.class),
                (Integer) r.getObject("present_count"), (Integer) r.getObject("late_count"), (Integer) r.getObject("absent_count"),
                (Integer) r.getObject("excused_count"), r.getInt("makeup_valid_days"), r.getObject("makeup_case_id", UUID.class),
                r.getString("makeup_status"))).list();
    }

    @Override public Optional<SessionState> lockSession(UUID sessionId) {
        return jdbc.sql("""
                select s.id,s.status,s.version,s.class_group_id,g.makeup_valid_days,s.attendance_date,
                       s.starts_at,s.ends_at,s.class_name_snapshot
                  from attendance_session s join class_group g on g.id=s.class_group_id
                 where s.id=:id for update of s
                """).param("id", sessionId).query((r, n) -> new SessionState(r.getObject("id", UUID.class),
                r.getString("status"), r.getLong("version"), r.getObject("class_group_id", UUID.class),
                r.getInt("makeup_valid_days"), r.getObject("attendance_date", LocalDate.class),
                r.getObject("starts_at", OffsetDateTime.class), r.getObject("ends_at", OffsetDateTime.class),
                r.getString("class_name_snapshot"))).optional();
    }

    @Override public Optional<TargetState> lockTarget(UUID sessionId, UUID studentId) {
        return jdbc.sql("""
                select t.student_id,t.student_name_snapshot,t.display_order,a.id attendance_id,a.status,
                       a.check_in_time,a.reason,a.makeup_eligible,a.version attendance_version
                  from attendance_session_student t
                  left join student_attendance a on a.attendance_session_id=t.attendance_session_id and a.student_id=t.student_id
                 where t.attendance_session_id=:session and t.student_id=:student for update of t
                """).param("session", sessionId).param("student", studentId).query((r, n) -> new TargetState(
                r.getObject("student_id", UUID.class), r.getString("student_name_snapshot"), r.getInt("display_order"),
                r.getObject("attendance_id", UUID.class), r.getString("status"), r.getObject("check_in_time", java.time.LocalTime.class),
                r.getString("reason"), r.getBoolean("makeup_eligible"), (Long) r.getObject("attendance_version"))).optional();
    }

    @Override public int insertAttendance(UUID sessionId, UUID studentId, UUID actorId, AttendanceWrite write) {
        return jdbc.sql("""
                insert into student_attendance(id,attendance_session_id,student_id,status,check_in_time,reason,makeup_eligible,checked_by,updated_by)
                values(:id,:session,:student,:status,:checkIn,:reason,:makeup,:actor,:actor)
                """).param("id", UUID.randomUUID()).param("session", sessionId).param("student", studentId)
                .param("status", write.status()).param("checkIn", write.checkInTime()).param("reason", normalized(write.reason()))
                .param("makeup", write.makeupEligible()).param("actor", actorId).update();
    }

    @Override public int updateAttendance(UUID sessionId, UUID studentId, UUID actorId, AttendanceWrite write) {
        return jdbc.sql("""
                update student_attendance set status=:status,check_in_time=:checkIn,reason=:reason,
                       makeup_eligible=:makeup,updated_by=:actor,version=version+1
                 where attendance_session_id=:session and student_id=:student and version=:version
                """).param("status", write.status()).param("checkIn", write.checkInTime()).param("reason", normalized(write.reason()))
                .param("makeup", write.makeupEligible()).param("actor", actorId).param("session", sessionId)
                .param("student", studentId).param("version", write.version()).update();
    }

    @Override public int incrementSessionVersion(UUID sessionId, long expectedVersion) {
        return jdbc.sql("update attendance_session set version=version+1 where id=:id and version=:version and status='OPEN'")
                .param("id", sessionId).param("version", expectedVersion).update();
    }

    @Override public List<TargetState> findTargets(UUID sessionId) {
        return jdbc.sql("""
                select t.student_id,t.student_name_snapshot,t.display_order,a.id attendance_id,a.status,
                       a.check_in_time,a.reason,a.makeup_eligible,a.version attendance_version
                  from attendance_session_student t
                  left join student_attendance a on a.attendance_session_id=t.attendance_session_id and a.student_id=t.student_id
                 where t.attendance_session_id=:session order by t.display_order,t.student_id
                """).param("session", sessionId).query((r, n) -> new TargetState(r.getObject("student_id", UUID.class),
                r.getString("student_name_snapshot"), r.getInt("display_order"), r.getObject("attendance_id", UUID.class),
                r.getString("status"), r.getObject("check_in_time", java.time.LocalTime.class), r.getString("reason"),
                r.getBoolean("makeup_eligible"), (Long) r.getObject("attendance_version"))).list();
    }

    @Override public int closeSession(UUID sessionId, long expectedVersion, UUID actorId, OffsetDateTime closedAt, Summary summary) {
        return jdbc.sql("""
                update attendance_session set status='CLOSED',closed_by=:actor,closed_at=:closedAt,
                       present_count=:present,late_count=:late,absent_count=:absent,excused_count=:excused,
                       version=version+1
                 where id=:id and status='OPEN' and version=:version
                """).param("actor", actorId).param("closedAt", closedAt).param("present", summary.presentCount())
                .param("late", summary.lateCount()).param("absent", summary.absentCount()).param("excused", summary.excusedCount())
                .param("id", sessionId).param("version", expectedVersion).update();
    }

    @Override public List<MakeupValue> createMakeupCases(UUID sessionId, UUID actorId, int validDays) {
        return jdbc.sql("""
                insert into makeup_case(id,student_id,origin_attendance_id,origin_session_id,class_group_id,status,expires_on,created_by,updated_by)
                select gen_random_uuid(),attendance.student_id,attendance.id,session.id,session.class_group_id,'AVAILABLE',
                       session.attendance_date + :validDays, :actor, :actor
                  from student_attendance attendance join attendance_session session on session.id=attendance.attendance_session_id
                 where session.id=:session and attendance.makeup_eligible
                on conflict (origin_attendance_id) do nothing
                returning id,student_id
                """).param("validDays", validDays).param("actor", actorId).param("session", sessionId)
                .query((r, n) -> new MakeupValue(r.getObject("id", UUID.class), r.getObject("student_id", UUID.class))).list();
    }

    @Override public Optional<AttendanceValues> findAttendance(UUID sessionId, UUID studentId) {
        return jdbc.sql("select id,status,check_in_time,reason,makeup_eligible,version,updated_at from student_attendance where attendance_session_id=:session and student_id=:student")
                .param("session", sessionId).param("student", studentId).query((r, n) -> new AttendanceValues(
                r.getObject("id", UUID.class), r.getString("status"), r.getObject("check_in_time", java.time.LocalTime.class),
                r.getString("reason"), r.getBoolean("makeup_eligible"), r.getLong("version"), r.getObject("updated_at", OffsetDateTime.class))).optional();
    }

    @Override public boolean claimIdempotency(String scope, UUID key, String requestHash) {
        return jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,expires_at)
                values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing
                """).param("scope", scope).param("key", key).param("hash", requestHash).update() == 1;
    }

    @Override public Optional<String> idempotencyHash(String scope, UUID key) {
        return jdbc.sql("select request_hash from idempotency_record where scope=:scope and idempotency_key=:key and expires_at>statement_timestamp()")
                .param("scope", scope).param("key", key).query(String.class).optional();
    }

    @Override public void completeIdempotency(String scope, UUID key, UUID resourceId, int responseStatus) {
        jdbc.sql("update idempotency_record set state='COMPLETED',response_status=:status,resource_id=:resource where scope=:scope and idempotency_key=:key")
                .param("status", responseStatus).param("resource", resourceId).param("scope", scope).param("key", key).update();
    }

    @Override public Optional<ClosureState> findClosure(UUID sessionId) {
        return jdbc.sql("""
                select s.closed_by,u.display_name,s.closed_at,s.target_count,s.present_count,s.late_count,s.absent_count,s.excused_count
                  from attendance_session s left join admin_user u on u.id=s.closed_by where s.id=:session and s.status='CLOSED'
                """).param("session", sessionId).query((r, n) -> new ClosureState(r.getObject("closed_by", UUID.class),
                r.getString("display_name"), r.getObject("closed_at", OffsetDateTime.class), r.getInt("target_count"),
                r.getInt("present_count"), r.getInt("late_count"), r.getInt("absent_count"), r.getInt("excused_count"))).optional();
    }

    @Override public String findAdminDisplayName(UUID adminUserId) {
        return jdbc.sql("select display_name from admin_user where id=:id")
                .param("id", adminUserId).query(String.class).single();
    }

    @Override public List<UUID> findMakeupIdsForOriginSession(UUID sessionId) {
        return jdbc.sql("select id from makeup_case where origin_session_id=:session order by id")
                .param("session", sessionId).query(UUID.class).list();
    }

    private static String normalized(String value) { return value == null ? null : value.trim(); }
}
