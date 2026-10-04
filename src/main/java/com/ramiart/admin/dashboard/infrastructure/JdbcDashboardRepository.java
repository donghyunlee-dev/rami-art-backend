package com.ramiart.admin.dashboard.infrastructure;

import com.ramiart.admin.dashboard.application.DashboardRepository;
import com.ramiart.admin.dashboard.application.DashboardRepository.AuditActivity;
import com.ramiart.admin.dashboard.application.DashboardRepository.AttendanceMetrics;
import com.ramiart.admin.dashboard.application.DashboardRepository.Birthday;
import com.ramiart.admin.dashboard.application.DashboardRepository.InquiryMetrics;
import com.ramiart.admin.dashboard.application.DashboardRepository.TuitionMetrics;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDashboardRepository implements DashboardRepository {
    private final JdbcClient jdbc;
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    public JdbcDashboardRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public AttendanceMetrics attendance(LocalDate date) {
        return jdbc.sql("""
                select coalesce(sum(s.target_count),0)::int scheduled_count,
                       coalesce(sum(case when s.status='CLOSED'
                           then coalesce(s.present_count,0)+coalesce(s.late_count,0)+coalesce(s.absent_count,0)+coalesce(s.excused_count,0)
                           when s.status='OPEN' then (select count(*) from student_attendance a where a.attendance_session_id=s.id)
                           else 0 end),0)::int completed_count
                  from attendance_session s
                 where s.attendance_date=:date and s.status in ('OPEN','CLOSED')
                """).param("date", date).query((row, index) ->
                new AttendanceMetrics(row.getInt("scheduled_count"), row.getInt("completed_count"))).single();
    }

    @Override
    public TuitionMetrics overdueTuition(LocalDate today) {
        return jdbc.sql("""
                select count(distinct b.student_id)::int overdue_student_count,
                       count(*)::int overdue_billing_count,
                       coalesce(sum(b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount),0)::bigint outstanding_amount
                  from tuition_billing b
                 where b.due_date < :today and b.payment_status <> 'CREDIT'
                   and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount > 0
                """).param("today", today).query((row, index) -> new TuitionMetrics(row.getInt("overdue_student_count"),
                row.getInt("overdue_billing_count"), row.getLong("outstanding_amount"))).single();
    }

    @Override
    public InquiryMetrics inquiries(LocalDate today, java.time.ZoneId studioZone) {
        LocalDateTime start = today.minusDays(90).atStartOfDay();
        LocalDateTime staleBefore = today.minusDays(3).atStartOfDay();
        return jdbc.sql("""
                select count(*) filter (where i.read_at is null)::int unread_count,
                       count(*)::int unanswered_count,
                       count(*) filter (where i.received_at < :stale_before)::int stale_count
                  from inquiry i
                 where i.status in ('RECEIVED','CONTACTING') and i.received_at >= :range_start
                """).param("range_start", start.atZone(studioZone).toOffsetDateTime())
                .param("stale_before", staleBefore.atZone(studioZone).toOffsetDateTime())
                .query((row, index) -> new InquiryMetrics(row.getInt("unread_count"),
                        row.getInt("unanswered_count"), row.getInt("stale_count"))).single();
    }

    @Override
    public List<Birthday> birthdays(LocalDate today, LocalDate rangeEnd, int limit) {
        List<BirthdayCandidate> candidates = jdbc.sql("""
                select id,student_name,birthday from student
                 where status='ACTIVE' and birthday is not null
                 order by student_name_search,id
                """).query((row, index) -> new BirthdayCandidate(row.getObject("id", java.util.UUID.class),
                row.getString("student_name"), row.getObject("birthday", LocalDate.class))).list();
        return candidates.stream().map(candidate -> birthday(candidate, today))
                .filter(item -> item != null && !item.date().isAfter(rangeEnd))
                .sorted(Comparator.comparingInt((BirthdayWithDate item) -> item.value().daysUntil())
                        .thenComparing(item -> item.value().studentName()).thenComparing(item -> item.value().studentId()))
                .limit(limit).map(BirthdayWithDate::value).toList();
    }

    @Override
    public List<AuditActivity> recentActivities(int limit) {
        return jdbc.sql("""
                select id,occurred_at,task_id,action,target_type,target_id,target_display,actor_display
                  from audit_log
                 where result='SUCCESS' and target_type is not null and target_id is not null
                 order by occurred_at desc,id desc limit :limit
                """).param("limit", limit).query((row, index) -> new AuditActivity(
                row.getObject("id", java.util.UUID.class), row.getObject("occurred_at", OffsetDateTime.class),
                row.getString("task_id"), row.getString("action"), row.getString("target_type"),
                row.getObject("target_id", java.util.UUID.class), row.getString("target_display"), row.getString("actor_display"))).list();
    }

    private static BirthdayWithDate birthday(BirthdayCandidate candidate, LocalDate today) {
        MonthDay monthDay = MonthDay.from(candidate.birthday());
        LocalDate next = atYear(monthDay, today.getYear());
        if (next.isBefore(today)) next = atYear(monthDay, today.getYear() + 1);
        if (next.isAfter(today.plusDays(6))) return null;
        String displayMonthDay = next.format(MONTH_DAY);
        return new BirthdayWithDate(new Birthday(candidate.id(), candidate.name(), displayMonthDay,
                (int) java.time.temporal.ChronoUnit.DAYS.between(today, next)), next);
    }

    private static LocalDate atYear(MonthDay day, int year) {
        if (day.equals(MonthDay.of(2, 29)) && !java.time.Year.isLeap(year)) return LocalDate.of(year, 2, 28);
        return day.atYear(year);
    }

    private record BirthdayCandidate(java.util.UUID id, String name, LocalDate birthday) {}
    private record BirthdayWithDate(Birthday value, LocalDate date) {}
}
