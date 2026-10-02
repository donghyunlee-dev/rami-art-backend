package com.ramiart.admin.attendance.infrastructure;

import com.ramiart.admin.attendance.application.AttendanceRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttendanceRepository implements AttendanceRepository {
    private final JdbcClient jdbc;
    public JdbcAttendanceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public List<StudentRow> findByDate(LocalDate date) {
        return jdbc.sql("""
            select s.id session_id,s.schedule_slot_id,s.attendance_date,s.class_name_snapshot,s.starts_at,s.ends_at,s.status session_status,s.version session_version,
                   t.display_order,t.student_id,t.student_name_snapshot,
                   a.status attendance_status,a.check_in_time,a.reason,coalesce(a.version,0) attendance_version,a.updated_at
              from attendance_session s
              left join attendance_session_student t on t.attendance_session_id=s.id
              left join student_attendance a on a.attendance_session_id=t.attendance_session_id and a.student_id=t.student_id
             where s.attendance_date=:date
             order by s.starts_at,s.id,t.display_order,t.student_id
            """).param("date", date).query((r, n) -> new StudentRow(
                r.getObject("session_id", java.util.UUID.class), r.getObject("schedule_slot_id", java.util.UUID.class), r.getObject("attendance_date", LocalDate.class),
                r.getString("class_name_snapshot"), r.getObject("starts_at", java.time.OffsetDateTime.class), r.getObject("ends_at", java.time.OffsetDateTime.class),
                r.getString("session_status"), r.getLong("session_version"), r.getInt("display_order"), r.getObject("student_id", java.util.UUID.class),
                r.getString("student_name_snapshot"), r.getString("attendance_status"), r.getObject("check_in_time", java.time.LocalTime.class), r.getString("reason"),
                r.getLong("attendance_version"), r.getObject("updated_at", java.time.OffsetDateTime.class))).list();
    }
}
