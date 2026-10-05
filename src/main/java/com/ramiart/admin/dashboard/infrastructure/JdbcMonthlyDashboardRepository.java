package com.ramiart.admin.dashboard.infrastructure;

import com.ramiart.admin.dashboard.application.MonthlyDashboardRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMonthlyDashboardRepository implements MonthlyDashboardRepository {
    private final JdbcClient jdbc;
    public JdbcMonthlyDashboardRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Map<String, Object> tuition(YearMonth month) {
        Map<String,Object> values = tuitionValues(month);
        Map<String,Object> previous = tuitionValues(month.minusMonths(1));
        Map<String,Object> result = new LinkedHashMap<>(values);
        result.put("previousMonth", previous);
        result.put("targetUrl", "/admin/tuition/billings?month=" + month);
        return result;
    }

    private Map<String,Object> tuitionValues(YearMonth month) {
        var row = jdbc.sql("""
                select coalesce(sum(b.billed_amount+b.adjustment_amount),0)::bigint charge_amount,
                       coalesce(sum(greatest(b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount,0)),0)::bigint outstanding_amount,
                       coalesce(sum(greatest(-(b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount),0)),0)::bigint credit_amount
                  from tuition_billing b where b.year_month=:month
                """).param("month", month.toString()).query((r,i)->new long[]{r.getLong("charge_amount"),r.getLong("outstanding_amount"),r.getLong("credit_amount")}).single();
        long paid = scalar("select coalesce(sum(amount),0)::bigint from tuition_payment where status='CONFIRMED' and paid_on>=:from and paid_on<:to", month);
        long refund = scalar("select coalesce(sum(amount),0)::bigint from tuition_refund where status='CONFIRMED' and refunded_on>=:from and refunded_on<:to", month);
        return Map.of("chargeAmount", row[0], "paidAmount", paid, "refundAmount", refund,
                "outstandingAmount", row[1], "creditAmount", row[2]);
    }

    @Override public Map<String, Object> attendance(YearMonth month) {
        var totals = jdbc.sql("""
                select coalesce(sum(target_count) filter(where status='CLOSED'),0)::int target_count,
                       coalesce(sum(present_count+late_count) filter(where status='CLOSED'),0)::int attended_count,
                       coalesce(sum(absent_count) filter(where status='CLOSED'),0)::int absent_count,
                       coalesce(sum(target_count) filter(where status='OPEN'),0)::int pending_count
                  from attendance_session where attendance_date>=:from and attendance_date<:to
                """).param("from", month.atDay(1)).param("to", month.plusMonths(1).atDay(1))
                .query((r,i)->new int[]{r.getInt("target_count"),r.getInt("attended_count"),r.getInt("absent_count"),r.getInt("pending_count")}).single();
        List<Map<String,Object>> daily = jdbc.sql("""
                select d.calendar_date::date calendar_date, coalesce(sum(s.target_count) filter(where s.status='CLOSED'),0)::int target_count,
                       coalesce(sum(s.present_count+s.late_count) filter(where s.status='CLOSED'),0)::int attended_count
                  from generate_series(:from::date,:to::date-1,interval '1 day') d(calendar_date)
                  left join attendance_session s on s.attendance_date=d.calendar_date::date
                 group by d.calendar_date order by d.calendar_date
                """).param("from",month.atDay(1)).param("to",month.plusMonths(1).atDay(1))
                .query((r,i)->Map.<String,Object>of("date",r.getObject("calendar_date",LocalDate.class),"targetCount",r.getInt("target_count"),"attendedCount",r.getInt("attended_count"))).list();
        Double rate = totals[0] == 0 ? null : (double) totals[1] / totals[0];
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("targetCount",totals[0]); result.put("attendedCount",totals[1]); result.put("absentCount",totals[2]);
        result.put("pendingCount",totals[3]); result.put("rate",rate); result.put("daily",daily);
        result.put("targetUrl","/admin/operations/attendance?month="+month);
        return result;
    }

    @Override public Map<String, Object> lessons(YearMonth month) {
        var values = jdbc.sql("""
                select count(*) filter(where s.status<>'CANCELLED')::int scheduled_count,
                       count(*) filter(where s.lesson_plan_item_id is not null and p.status='PUBLISHED')::int planned_count,
                       count(*) filter(where s.status='CLOSED')::int closed_count,
                       count(*) filter(where s.status='CLOSED' and l.id is not null)::int finalized_count
                  from attendance_session s
                  left join lesson_plan_item pi on pi.id=s.lesson_plan_item_id
                  left join lesson_plan p on p.id=pi.lesson_plan_id
                  left join lateral (select id from lesson_log where attendance_session_id=s.id and status in ('FINALIZED','AMENDED') order by revision desc limit 1) l on true
                 where s.attendance_date>=:from and s.attendance_date<:to
                """).param("from",month.atDay(1)).param("to",month.plusMonths(1).atDay(1))
                .query((r,i)->new int[]{r.getInt("scheduled_count"),r.getInt("planned_count"),r.getInt("closed_count"),r.getInt("finalized_count")}).single();
        return Map.of("scheduledCount",values[0],"plannedCount",values[1],"closedCount",values[2],
                "finalizedLogCount",values[3],"missingLogCount",Math.max(0,values[2]-values[3]),
                "targetUrl","/admin/operations/lesson-logs?month="+month);
    }

    @Override public Map<String, Object> enrollment(YearMonth month, ZoneId zone) {
        var row = jdbc.sql("""
                select (select count(*) from enrollment_case where created_at>=:start and created_at<:end)::int new_count,
                       (select count(*) from enrollment_case where status in ('TRIAL_SCHEDULED','TRIAL_COMPLETED') and trial_starts_at>=:start and trial_starts_at<:end)::int trial_count,
                       (select count(*) from enrollment_case where status='WAITLISTED')::int waitlisted_count,
                       (select count(*) from enrollment_activity where type='ENROLLED' and occurred_at>=:start and occurred_at<:end)::int enrolled_count,
                       (select count(*) from enrollment_case where status='LOST' and updated_at>=:start and updated_at<:end)::int lost_count
                """).param("start",month.atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .param("end",month.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .query((r,i)->new int[]{r.getInt("new_count"),r.getInt("trial_count"),r.getInt("waitlisted_count"),r.getInt("enrolled_count"),r.getInt("lost_count")}).single();
        Double rate=row[0]==0?null:(double)row[3]/row[0]; Map<String,Object> result=new LinkedHashMap<>();
        result.put("newCount",row[0]);result.put("trialCount",row[1]);result.put("waitlistedCount",row[2]);result.put("enrolledCount",row[3]);result.put("lostCount",row[4]);result.put("conversionRate",rate);result.put("targetUrl","/admin/enrollments?month="+month);return result;
    }

    @Override public Map<String, Object> capacity(LocalDate asOfDate) {
        List<Map<String,Object>> items=jdbc.sql("""
                select g.id,g.code,g.name,g.capacity,
                       (select count(*)::int from student_schedule_assignment a join schedule_slot sl on sl.id=a.schedule_slot_id
                         where sl.class_group_id=g.id and sl.status='ACTIVE' and a.effective_from<=:as_of and (a.effective_to is null or a.effective_to>=:as_of)) student_count,
                       (select count(*)::int from makeup_case m where m.class_group_id=g.id and m.status='RESERVED') reserved_makeup_count
                  from class_group g where g.status='ACTIVE' and g.starts_on<=:as_of and (g.ends_on is null or g.ends_on>=:as_of)
                 order by g.name,g.id
                """).param("as_of",asOfDate).query((r,i)->Map.<String,Object>of("classGroupId",r.getObject("id",java.util.UUID.class),"code",r.getString("code"),"name",r.getString("name"),"capacity",r.getInt("capacity"),"studentCount",r.getInt("student_count"),"reservedMakeupCount",r.getInt("reserved_makeup_count"),"occupiedCount",r.getInt("student_count")+r.getInt("reserved_makeup_count"))).list();
        return Map.of("items",items,"targetUrl","/admin/operations/courses");
    }

    private long scalar(String sql,YearMonth month){return jdbc.sql(sql).param("from",month.atDay(1)).param("to",month.plusMonths(1).atDay(1)).query(Long.class).single();}
}
