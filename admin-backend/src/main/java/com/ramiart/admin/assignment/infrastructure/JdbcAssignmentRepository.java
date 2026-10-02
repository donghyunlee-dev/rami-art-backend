package com.ramiart.admin.assignment.infrastructure;

import static com.ramiart.admin.assignment.application.AssignmentModels.*;

import com.ramiart.admin.assignment.application.AssignmentException;
import com.ramiart.admin.assignment.application.AssignmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssignmentRepository implements AssignmentRepository {
    private final JdbcClient jdbc;

    public JdbcAssignmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<StudentRecord> findStudent(UUID id, boolean lock) {
        return jdbc.sql("select id,student_name,status from student where id=:id" + (lock ? " for update" : ""))
                .param("id", id).query((row, number) -> new StudentRecord(
                        row.getObject("id", UUID.class), row.getString("student_name"), row.getString("status")))
                .optional();
    }

    @Override
    public List<AssignmentRecord> findByStudent(UUID studentId, boolean includeEnded, LocalDate today) {
        return jdbc.sql(baseSelect() + " where a.student_id=:student and (:ended or a.effective_to is null or a.effective_to>=:today) "
                + "order by case when :today between a.effective_from and coalesce(a.effective_to,'infinity'::date) then 0 "
                + "when a.effective_from>:today then 1 else 2 end,a.effective_from,a.id")
                .param("student", studentId).param("ended", includeEnded).param("today", today)
                .query(this::map).list();
    }

    @Override
    public Optional<AssignmentRecord> find(UUID id, boolean lock) {
        return jdbc.sql(baseSelect() + " where a.id=:id" + (lock ? " for update of a" : ""))
                .param("id", id).query(this::map).optional();
    }

    @Override
    public List<CandidateView> findCandidates(UUID studentId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select distinct on (ss.id) ss.id,i.title,i.day_of_week,i.start_time,i.end_time,i.room_code
                from schedule_slot ss
                join monthly_schedule_item i on i.schedule_slot_id=ss.id
                join monthly_schedule m on m.id=i.monthly_schedule_id and m.status='PUBLISHED'
                where ss.status='ACTIVE' and m.year_month between to_char(:from,'YYYY-MM') and to_char(:to,'YYYY-MM')
                order by ss.id,m.year_month desc,m.revision desc
                """).param("from", from).param("to", to).query((row, number) -> {
                    UUID slot = row.getObject("id", UUID.class);
                    List<ConflictView> conflicts = findConflicts(studentId, slot, from, to, null);
                    return new CandidateView(slot, true, row.getString("title"), row.getInt("day_of_week"),
                            row.getObject("start_time", LocalTime.class), row.getObject("end_time", LocalTime.class),
                            row.getString("room_code"), conflicts);
                }).list();
    }

    @Override
    public boolean slotAssignable(UUID slotId, LocalDate from, LocalDate to) {
        LocalDate until = to == null ? from.plusYears(1) : to;
        return jdbc.sql("""
                select exists(select 1 from schedule_slot ss join monthly_schedule_item i on i.schedule_slot_id=ss.id
                join monthly_schedule m on m.id=i.monthly_schedule_id and m.status='PUBLISHED'
                where ss.id=:slot and ss.status='ACTIVE'
                  and m.year_month between to_char(:from,'YYYY-MM') and to_char(:until,'YYYY-MM'))
                """).param("slot", slotId).param("from", from).param("until", until).query(Boolean.class).single();
    }

    @Override
    public List<ConflictView> findConflicts(UUID studentId, UUID slotId, LocalDate from, LocalDate to, UUID excludedId) {
        LocalDate until = to == null ? LocalDate.of(9999, 12, 31) : to;
        return jdbc.sql("""
                with requested as (
                  select i.day_of_week,i.start_time,i.end_time from monthly_schedule_item i
                  join monthly_schedule m on m.id=i.monthly_schedule_id and m.status='PUBLISHED'
                  where i.schedule_slot_id=:slot order by m.year_month desc,m.revision desc limit 1
                ), existing as (
                  select a.id,a.effective_from,a.effective_to,i.title,i.day_of_week,i.start_time,i.end_time,i.room_code,
                    row_number() over(partition by a.id order by m.year_month desc,m.revision desc) rn
                  from student_schedule_assignment a join monthly_schedule_item i on i.schedule_slot_id=a.schedule_slot_id
                  join monthly_schedule m on m.id=i.monthly_schedule_id and m.status='PUBLISHED'
                  where a.student_id=:student and (cast(:excluded as uuid) is null or a.id<>cast(:excluded as uuid))
                    and daterange(a.effective_from,coalesce(a.effective_to,'infinity'::date),'[]') && daterange(:from,:until,'[]')
                )
                select e.* from existing e cross join requested r where e.rn=1 and
                  (e.id in (select id from student_schedule_assignment where schedule_slot_id=:slot)
                   or (e.day_of_week=r.day_of_week and e.start_time<r.end_time and r.start_time<e.end_time))
                order by e.effective_from,e.id
                """).param("slot", slotId).param("student", studentId).param("excluded", excludedId)
                .param("from", from).param("until", until).query((row, number) -> new ConflictView(
                        row.getObject("id", UUID.class), row.getString("title") + " · " + row.getString("room_code"),
                        row.getInt("day_of_week"), row.getObject("start_time", LocalTime.class),
                        row.getObject("end_time", LocalTime.class), row.getObject("effective_from", LocalDate.class),
                        row.getObject("effective_to", LocalDate.class))).list();
    }

    @Override
    public UUID insert(UUID studentId, CreateCommand command, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into student_schedule_assignment(id,student_id,schedule_slot_id,effective_from,effective_to,created_by,updated_by) values(:id,:student,:slot,:from,:to,:actor,:actor)")
                .param("id", id).param("student", studentId).param("slot", command.scheduleSlotId())
                .param("from", command.effectiveFrom()).param("to", command.effectiveTo()).param("actor", actorId).update();
        return id;
    }

    @Override
    public int update(UUID id, UpdateCommand command, String reason, UUID actorId) {
        return jdbc.sql("update student_schedule_assignment set effective_from=:from,effective_to=:to,ended_reason=:reason,updated_by=:actor,version=version+1 where id=:id and version=:version")
                .param("from", command.effectiveFrom()).param("to", command.effectiveTo()).param("reason", reason)
                .param("actor", actorId).param("id", id).param("version", command.version()).update();
    }

    @Override
    public int deleteFuture(UUID id, long version, LocalDate today) {
        return jdbc.sql("delete from student_schedule_assignment where id=:id and version=:version and effective_from>:today and not exists(select 1 from attendance_session_student where assignment_id=:id)")
                .param("id", id).param("version", version).param("today", today).update();
    }

    @Override
    public Claim claim(String scope, UUID key, String hash) {
        try {
            jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')")
                    .param("scope", scope).param("key", key).param("hash", hash).update();
            return new Claim(true, null);
        } catch (DuplicateKeyException exception) {
            Object[] row = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                    .param("scope", scope).param("key", key).query((result, number) -> new Object[]{result.getString(1), result.getString(2), result.getObject(3, UUID.class)}).single();
            if (!hash.equals(row[0])) throw new AssignmentException("IDEMPOTENCY_KEY_REUSED");
            if (!"COMPLETED".equals(row[1])) throw new AssignmentException("IDEMPOTENCY_IN_PROGRESS");
            return new Claim(false, (UUID) row[2]);
        }
    }

    @Override
    public void complete(String scope, UUID key, UUID resourceId, int status) {
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key")
                .param("resource", resourceId).param("status", status).param("scope", scope).param("key", key).update();
    }

    private String baseSelect() {
        return """
                select a.*,ss.status slot_status,coalesce(i.title,'종료된 수업') title,coalesce(i.day_of_week,1) day_of_week,
                  coalesce(i.start_time,'00:00'::time) start_time,coalesce(i.end_time,'00:05'::time) end_time,
                  coalesce(i.room_code,'-') room_code,
                  (select min(s.attendance_date) from attendance_session_student t join attendance_session s on s.id=t.attendance_session_id where t.assignment_id=a.id) first_snapshot,
                  (select max(s.attendance_date) from attendance_session_student t join attendance_session s on s.id=t.attendance_session_id where t.assignment_id=a.id) last_snapshot
                from student_schedule_assignment a join schedule_slot ss on ss.id=a.schedule_slot_id
                left join lateral (select i.* from monthly_schedule_item i join monthly_schedule m on m.id=i.monthly_schedule_id
                  where i.schedule_slot_id=a.schedule_slot_id and m.status='PUBLISHED' order by m.year_month desc,m.revision desc limit 1) i on true
                """;
    }

    private AssignmentRecord map(ResultSet row, int number) throws SQLException {
        return new AssignmentRecord(row.getObject("id", UUID.class), row.getObject("student_id", UUID.class),
                row.getObject("schedule_slot_id", UUID.class), schedule(row, "ACTIVE".equals(row.getString("slot_status"))),
                row.getObject("effective_from", LocalDate.class), row.getObject("effective_to", LocalDate.class),
                row.getString("ended_reason"), row.getLong("version"), row.getObject("first_snapshot", LocalDate.class),
                row.getObject("last_snapshot", LocalDate.class));
    }

    private static ScheduleView schedule(ResultSet row, boolean available) throws SQLException {
        return new ScheduleView(available, row.getString("title"), row.getInt("day_of_week"),
                row.getObject("start_time", LocalTime.class), row.getObject("end_time", LocalTime.class), row.getString("room_code"));
    }
}
