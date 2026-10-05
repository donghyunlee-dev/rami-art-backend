package com.ramiart.admin.makeup.infrastructure;

import static com.ramiart.admin.makeup.application.MakeupModels.*;
import com.ramiart.admin.makeup.application.MakeupRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMakeupRepository implements MakeupRepository {
    private final JdbcClient jdbc;
    public JdbcMakeupRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public CasePage list(String status, LocalDate from, LocalDate to, UUID studentId, int page, int size) {
        String where = " where (cast(:status as varchar) is null or m.status=cast(:status as varchar)) and (cast(:from_date as date) is null or origin.attendance_date>=cast(:from_date as date)) and (cast(:to_date as date) is null or origin.attendance_date<=cast(:to_date as date)) and (cast(:student as uuid) is null or m.student_id=cast(:student as uuid)) ";
        var params = jdbc.sql("select count(*) from makeup_case m join attendance_session origin on origin.id=m.origin_session_id" + where)
                .param("status", status).param("from_date", from).param("to_date", to).param("student", studentId);
        long total = params.query(Long.class).single();
        List<CaseRow> items = rows(jdbc.sql("""
                select m.id,m.student_id,origin_target.student_name_snapshot student_name,m.origin_session_id,origin.attendance_date,
                       m.class_group_id,origin.class_name_snapshot,m.status,m.expires_on,m.reserved_session_id,m.attempt_count,m.version,m.created_at
                  from makeup_case m join attendance_session origin on origin.id=m.origin_session_id
                  join attendance_session_student origin_target on origin_target.attendance_session_id=m.origin_session_id and origin_target.student_id=m.student_id
                """ + where + " order by m.created_at desc,m.id limit :limit offset :offset")
                .param("status", status).param("from_date", from).param("to_date", to).param("student", studentId)
                .param("limit", size).param("offset", (long) page * size));
        return new CasePage(items, page, size, total);
    }

    @Override public Optional<Detail> detail(UUID id) {
        List<CaseRow> found = rows(jdbc.sql("""
                select m.id,m.student_id,t.student_name_snapshot student_name,m.origin_session_id,o.attendance_date,
                       m.class_group_id,o.class_name_snapshot,m.status,m.expires_on,m.reserved_session_id,m.attempt_count,m.version,m.created_at,
                       m.origin_attendance_id,oa.status origin_status,oa.reason origin_reason,m.completed_attendance_id
                  from makeup_case m join attendance_session o on o.id=m.origin_session_id
                  join attendance_session_student t on t.attendance_session_id=m.origin_session_id and t.student_id=m.student_id
                  join student_attendance oa on oa.id=m.origin_attendance_id where m.id=:id
                """).param("id", id));
        if (found.isEmpty()) return Optional.empty();
        var row = found.getFirst();
        var meta = jdbc.sql("select m.origin_attendance_id,oa.status,oa.reason,m.completed_attendance_id from makeup_case m join student_attendance oa on oa.id=m.origin_attendance_id where m.id=:id")
                .param("id", id).query((r, n) -> new Object[]{r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getObject(4, UUID.class)}).single();
        List<Attempt> history = jdbc.sql("""
                select * from (
                    select s.id,s.attendance_date,s.class_name_snapshot,m.status,a.id attendance_id,a.status attendance_status,a.updated_at,s.starts_at
                      from attendance_session_student t join attendance_session s on s.id=t.attendance_session_id
                      left join makeup_case m on m.id=t.makeup_case_id
                      left join student_attendance a on a.attendance_session_id=t.attendance_session_id and a.student_id=t.student_id
                     where t.makeup_case_id=:id
                    union all
                    select s.id,s.attendance_date,s.class_name_snapshot,'CANCELLED'::varchar,null::uuid,null::varchar,log.occurred_at,s.starts_at
                      from audit_log log join attendance_session s on s.id=(log.details->>'sessionId')::uuid
                     where log.target_id=:id and log.action='MAKEUP_RESERVATION_CANCELLED'
                ) attempts order by starts_at,id
                """).param("id", id).query((r, n) -> new Attempt(r.getObject("id", UUID.class), r.getObject("attendance_date", LocalDate.class),
                r.getString("class_name_snapshot"), r.getString("status"), r.getObject("attendance_id", UUID.class), r.getString("attendance_status"),
                r.getObject("updated_at", java.time.OffsetDateTime.class))).list();
        return Optional.of(new Detail(row, (UUID) meta[0], (String) meta[1], (String) meta[2], (UUID) meta[3], history));
    }

    @Override public List<Candidate> candidates(UUID caseId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select s.id,s.starts_at,s.class_group_id,s.class_name_snapshot,s.version,g.capacity,
                       count(t.student_id) filter(where t.makeup_case_id is null)::int regular_count,
                       count(t.student_id) filter(where t.makeup_case_id is not null)::int makeup_count,
                       (exists(select 1 from attendance_session_student conflict_target where conflict_target.attendance_session_id=s.id and conflict_target.student_id=m.student_id)
                        or exists(select 1 from student_schedule_assignment a join schedule_slot sl on sl.id=a.schedule_slot_id join monthly_schedule_item i on i.schedule_slot_id=sl.id
                                   where a.student_id=m.student_id and a.effective_from<=s.attendance_date and (a.effective_to is null or a.effective_to>=s.attendance_date)
                                     and i.day_of_week=extract(isodow from s.attendance_date)::int
                                     and (s.starts_at at time zone 'Asia/Seoul')::time<i.end_time and (s.ends_at at time zone 'Asia/Seoul')::time>i.start_time)) student_conflict,
                       (g.course_id=origin_group.course_id) compatible
                  from makeup_case m join class_group origin_group on origin_group.id=m.class_group_id
                  join attendance_session s on true
                  join class_group g on g.id=s.class_group_id and g.course_id=origin_group.course_id
                  left join attendance_session_student t on t.attendance_session_id=s.id
                 where m.id=:case and s.attendance_date between :from_date and :to_date and s.starts_at>statement_timestamp() and s.status='OPEN' and m.status='AVAILABLE'
                 group by s.id,s.starts_at,s.class_group_id,s.class_name_snapshot,s.version,g.capacity,m.student_id,m.class_group_id,origin_group.course_id,g.course_id
                 order by s.starts_at,s.id
                """).param("case", caseId).param("from_date", from).param("to_date", to).query((r, n) -> {
                    int regular = r.getInt("regular_count"), reserved = r.getInt("makeup_count"), capacity = r.getInt("capacity");
                    boolean conflict = r.getBoolean("student_conflict"), compatible = r.getBoolean("compatible");
                    int available = Math.max(0, capacity - regular - reserved);
                    java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
                    if (!compatible) reasons.add("SESSION_INCOMPATIBLE");
                    if (available == 0) reasons.add("CAPACITY_FULL");
                    if (conflict) reasons.add("STUDENT_TIME_CONFLICT");
                    return new Candidate(r.getObject("id", UUID.class), r.getObject("starts_at", java.time.OffsetDateTime.class),
                            r.getObject("class_group_id", UUID.class), r.getString("class_name_snapshot"), compatible, regular, reserved,
                            capacity, available, conflict, reasons.isEmpty(), List.copyOf(reasons), r.getLong("version"));
                }).list();
    }

    private static List<CaseRow> rows(JdbcClient.StatementSpec statement) {
        return statement.query((r, n) -> new CaseRow(r.getObject("id", UUID.class), r.getObject("student_id", UUID.class),
                r.getString("student_name"), r.getObject("origin_session_id", UUID.class), r.getObject("attendance_date", LocalDate.class),
                r.getObject("class_group_id", UUID.class), r.getString("class_name_snapshot"), r.getString("status"), r.getObject("expires_on", LocalDate.class),
                r.getObject("reserved_session_id", UUID.class), r.getInt("attempt_count"), r.getLong("version"), r.getObject("created_at", java.time.OffsetDateTime.class))).list();
    }

    @Override public Optional<LockedCase> findCase(UUID id) { return jdbc.sql("select m.id,m.student_id,m.status,m.expires_on,m.origin_session_id,m.class_group_id,g.course_id,m.version,m.reserved_session_id from makeup_case m join class_group g on g.id=m.class_group_id where m.id=:id").param("id",id).query((r,n)->new LockedCase(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,LocalDate.class),r.getObject(5,UUID.class),r.getObject(6,UUID.class),r.getObject(7,UUID.class),r.getLong(8),r.getObject(9,UUID.class))).optional(); }
    @Override public Optional<LockedCase> lockCase(UUID id) { return jdbc.sql("select m.id,m.student_id,m.status,m.expires_on,m.origin_session_id,m.class_group_id,g.course_id,m.version,m.reserved_session_id from makeup_case m join class_group g on g.id=m.class_group_id where m.id=:id for update of m").param("id",id).query((r,n)->new LockedCase(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,LocalDate.class),r.getObject(5,UUID.class),r.getObject(6,UUID.class),r.getObject(7,UUID.class),r.getLong(8),r.getObject(9,UUID.class))).optional(); }
    @Override public Optional<LockedSession> lockSession(UUID id) { return jdbc.sql("select s.id,s.class_group_id,g.course_id,s.class_name_snapshot,s.attendance_date,s.starts_at,s.ends_at,s.status,s.version,g.capacity from attendance_session s join class_group g on g.id=s.class_group_id where s.id=:id for update of s").param("id",id).query((r,n)->new LockedSession(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getObject(5,LocalDate.class),r.getObject(6,java.time.OffsetDateTime.class),r.getObject(7,java.time.OffsetDateTime.class),r.getString(8),r.getLong(9),r.getInt(10))).optional(); }
    @Override public int reserve(UUID caseId, LockedCase c, LockedSession s, UUID actor) {
        int regular=jdbc.sql("select count(*)::int from attendance_session_student where attendance_session_id=:session and makeup_case_id is null").param("session",s.id()).query(Integer.class).single();
        int makeups=jdbc.sql("select count(*)::int from makeup_case where reserved_session_id=:session and status='RESERVED'").param("session",s.id()).query(Integer.class).single();
        if (regular+makeups>=s.capacity()) return 0;
        if (jdbc.sql("select exists(select 1 from attendance_session_student where attendance_session_id=:session and student_id=:student) or exists(select 1 from student_schedule_assignment a join schedule_slot sl on sl.id=a.schedule_slot_id join monthly_schedule_item i on i.schedule_slot_id=sl.id where a.student_id=:student and a.effective_from<=:date and (a.effective_to is null or a.effective_to>=:date) and i.day_of_week=extract(isodow from :date::date)::int and :start_time::time < i.end_time and :end_time::time > i.start_time)").param("session",s.id()).param("student",c.studentId()).param("date",s.date()).param("start_time",s.startsAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()).param("end_time",s.endsAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()).query(Boolean.class).single()) return -1;
        int inserted=jdbc.sql("insert into attendance_session_student(attendance_session_id,student_id,student_name_snapshot,display_order,makeup_case_id) select :session,:student,student.student_name,coalesce((select max(display_order)+1 from attendance_session_student where attendance_session_id=:session),0),:case from student where student.id=:student").param("session",s.id()).param("student",c.studentId()).param("case",caseId).update();
        if(inserted!=1) return -1;
        int updated=jdbc.sql("update makeup_case set status='RESERVED',reserved_session_id=:session,updated_by=:actor,version=version+1 where id=:case and status='AVAILABLE' and version=:version").param("session",s.id()).param("actor",actor).param("case",caseId).param("version",c.version()).update();
        if(updated==1) jdbc.sql("update attendance_session set target_count=target_count+1,version=version+1 where id=:id and status='OPEN' and version=:version").param("id",s.id()).param("version",s.version()).update();
        return updated;
    }
    @Override public int cancelReservation(UUID caseId,LockedCase c,UUID actor) {
        int removed=jdbc.sql("delete from attendance_session_student where attendance_session_id=:session and student_id=:student and makeup_case_id=:case and not exists(select 1 from student_attendance a where a.attendance_session_id=:session and a.student_id=:student)")
                .param("session",c.reservedSessionId()).param("student",c.studentId()).param("case",caseId).update();
        if(removed!=1)return 0;
        jdbc.sql("update attendance_session set target_count=greatest(0,target_count-1),version=version+1 where id=:id and status='OPEN'")
                .param("id",c.reservedSessionId()).update();
        return jdbc.sql("update makeup_case set status='AVAILABLE',reserved_session_id=null,updated_by=:actor,version=version+1 where id=:id and status='RESERVED' and version=:version")
                .param("actor",actor).param("id",caseId).param("version",c.version()).update();
    }
    @Override public int waive(UUID id,long version,String reason,UUID actor) { return jdbc.sql("update makeup_case set status='WAIVED',waive_reason=:reason,updated_by=:actor,version=version+1 where id=:id and status in ('AVAILABLE','EXPIRED') and version=:version").param("reason",reason.trim()).param("actor",actor).param("id",id).param("version",version).update(); }
    @Override public int extend(UUID id,long version,LocalDate expiresOn,String reason,UUID actor) { return jdbc.sql("update makeup_case set status='AVAILABLE',expires_on=:expiry,extended_reason=:reason,updated_by=:actor,version=version+1 where id=:id and status in ('AVAILABLE','EXPIRED') and version=:version and expires_on<:expiry").param("expiry",expiresOn).param("reason",reason.trim()).param("actor",actor).param("id",id).param("version",version).update(); }
    @Override public List<ExpiredCase> expireAvailableCases(LocalDate today,int limit) {
        return jdbc.sql("""
                with selected as (
                    select id from makeup_case where status='AVAILABLE' and expires_on<:today
                    order by expires_on,id for update skip locked limit :limit
                )
                update makeup_case m set status='EXPIRED',updated_by=m.created_by,version=m.version+1
                  from selected where m.id=selected.id
                returning m.id,m.created_by
                """).param("today",today).param("limit",limit).query((r,n)->new ExpiredCase(r.getObject("id",UUID.class),r.getObject("created_by",UUID.class))).list();
    }
    @Override public boolean claim(String scope,UUID key,String hash) { return jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,expires_at) values(:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing").param("scope",scope).param("key",key).param("hash",hash).update()==1; }
    @Override public Optional<String> hash(String scope,UUID key) { return jdbc.sql("select request_hash from idempotency_record where scope=:scope and idempotency_key=:key and expires_at>statement_timestamp()").param("scope",scope).param("key",key).query(String.class).optional(); }
    @Override public void complete(String scope,UUID key,UUID resource,int status) { jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key").param("resource",resource).param("status",status).param("scope",scope).param("key",key).update(); }
}
