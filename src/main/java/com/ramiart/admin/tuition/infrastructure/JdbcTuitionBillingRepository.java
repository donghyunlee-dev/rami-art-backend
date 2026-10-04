package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.tuition.application.TuitionBillingRepository;
import com.ramiart.admin.tuition.application.TuitionBillingRepository.BillingDetail;
import com.ramiart.admin.tuition.application.TuitionBillingRepository.BillingRow;
import com.ramiart.admin.tuition.application.TuitionBillingRepository.PreviewState;
import com.ramiart.admin.tuition.application.TuitionBillingRepository.PreviewStudent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.YearMonth;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTuitionBillingRepository implements TuitionBillingRepository {
    private final JdbcClient jdbc;
    public JdbcTuitionBillingRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public List<BillingRow> list(String yearMonth, boolean overdueOnly, List<String> statuses,
            LocalDate fromMonth, LocalDate throughDate, LocalDate today, int limit, long offset) {
        return jdbc.sql("""
                select b.id billing_id,b.student_id,s.student_name,b.year_month,
                       tp.year::text || ' rev' || tp.revision || ' · 주 ' || tpi.lesson_count_per_week || '회' policy_label,
                       '주 ' || tpi.lesson_count_per_week || '회 기본' assignment_label,
                       b.billed_amount,b.adjustment_amount,b.paid_amount,b.refunded_amount,b.due_date,
                       b.payment_status,b.issued_at,b.version
                  from tuition_billing b
                  join student s on s.id=b.student_id
                  join student_tuition_assignment a on a.id=b.tuition_assignment_id
                  join tuition_policy_item tpi on tpi.id=b.policy_item_id
                  join tuition_policy tp on tp.id=tpi.tuition_policy_id
                 where (:year_month='' or b.year_month=:year_month)
                   and (:overdue_only=false or (b.due_date < :today and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount>0))
                   and (cardinality(cast(:statuses as text[]))=0
                        or (('OVERDUE'=any(cast(:statuses as text[])) and b.due_date < :today
                             and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount>0)
                            or b.payment_status=any(cast(:statuses as text[]))))
                   and (:year_month<>'' or (b.due_date >= :from_month and b.due_date <= :through_date))
                 order by b.due_date asc,s.student_name_search asc,b.student_id asc
                 limit :limit offset :offset
                """).param("year_month", yearMonth==null?"":yearMonth).param("overdue_only", overdueOnly)
                .param("statuses", statuses.toArray(String[]::new)).param("today", today)
                .param("from_month", fromMonth).param("through_date", throughDate)
                .param("limit", limit).param("offset", offset)
                .query((row, index) -> new BillingRow(row.getObject("billing_id", UUID.class),
                        row.getObject("student_id", UUID.class), row.getString("student_name"), row.getString("year_month").trim(),
                        row.getString("policy_label"), row.getString("assignment_label"), row.getLong("billed_amount"),
                        row.getLong("adjustment_amount"), row.getLong("paid_amount"), row.getLong("refunded_amount"),
                        row.getObject("due_date", LocalDate.class), row.getString("payment_status"),
                        row.getObject("issued_at", java.time.OffsetDateTime.class), row.getLong("version"))).list();
    }

    @Override
    public Map<String, Object> summarize(String yearMonth, boolean overdueOnly, List<String> statuses,
            LocalDate fromMonth, LocalDate throughDate, LocalDate today) {
        return jdbc.sql("""
                select count(*)::bigint count,
                       coalesce(sum(b.billed_amount),0)::bigint billed_amount,
                       coalesce(sum(b.paid_amount),0)::bigint paid_amount,
                       coalesce(sum(b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount),0)::bigint balance,
                       coalesce(sum(b.adjustment_amount),0)::bigint adjustment_amount,
                       coalesce(sum(b.billed_amount+b.adjustment_amount),0)::bigint charge_amount,
                       coalesce(sum(b.paid_amount-b.refunded_amount),0)::bigint net_paid_amount,
                       coalesce(sum(greatest(-(b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount),0)),0)::bigint credit_amount
                  from tuition_billing b
                 where (:year_month='' or b.year_month=:year_month)
                   and (:overdue_only=false or (b.due_date < :today and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount>0))
                   and (cardinality(cast(:statuses as text[]))=0
                        or (('OVERDUE'=any(cast(:statuses as text[])) and b.due_date < :today
                             and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount>0)
                            or b.payment_status=any(cast(:statuses as text[]))))
                   and (:year_month<>'' or (b.due_date >= :from_month and b.due_date <= :through_date))
                """).param("year_month", yearMonth==null?"":yearMonth).param("overdue_only", overdueOnly)
                .param("statuses", statuses.toArray(String[]::new)).param("today", today)
                .param("from_month", fromMonth).param("through_date", throughDate)
                .query((row, index) -> Map.<String,Object>of("count",row.getLong("count"),
                        "billedAmount",row.getLong("billed_amount"),"paidAmount",row.getLong("paid_amount"),
                        "balance",row.getLong("balance"),"confirmedAdjustmentAmount",row.getLong("adjustment_amount"),
                        "chargeAmount",row.getLong("charge_amount"),"netPaidAmount",row.getLong("net_paid_amount"),
                        "creditAmount",row.getLong("credit_amount"))).single();
    }

    @Override
    public long count(String yearMonth, boolean overdueOnly, List<String> statuses,
            LocalDate fromMonth, LocalDate throughDate, LocalDate today) {
        return jdbc.sql("""
                select count(*) from tuition_billing b
                 where (:year_month='' or b.year_month=:year_month)
                   and (:overdue_only=false or (b.due_date < :today and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount>0))
                   and (cardinality(cast(:statuses as text[]))=0
                        or (('OVERDUE'=any(cast(:statuses as text[])) and b.due_date < :today
                             and b.billed_amount+b.adjustment_amount-b.paid_amount+b.refunded_amount>0)
                            or b.payment_status=any(cast(:statuses as text[]))))
                   and (:year_month<>'' or (b.due_date >= :from_month and b.due_date <= :through_date))
                """).param("year_month", yearMonth==null?"":yearMonth).param("overdue_only", overdueOnly)
                .param("statuses", statuses.toArray(String[]::new)).param("today", today)
                .param("from_month", fromMonth).param("through_date", throughDate)
                .query(Long.class).single();
    }

    @Override
    public Optional<BillingDetail> detail(UUID billingId) {
        return jdbc.sql("""
                select b.id billing_id,b.student_id,s.student_name,b.year_month,s.status student_status,
                       tp.year::text || ' rev' || tp.revision || ' · 주 ' || tpi.lesson_count_per_week || '회' policy_label,
                       tpi.id policy_item_id,tpi.monthly_amount,tp.default_due_day,tpi.lesson_count_per_week,
                       a.id assignment_id,b.override_amount_snapshot override_amount,
                       b.override_reason_snapshot override_reason,b.assignment_version,
                       b.billed_amount,b.adjustment_amount,b.paid_amount,b.refunded_amount,b.due_date,b.payment_status,
                       b.issued_at,b.issued_by,issuer.display_name issued_by_name,b.billing_batch_id,b.version
                  from tuition_billing b join student s on s.id=b.student_id
                  join student_tuition_assignment a on a.id=b.tuition_assignment_id
                  join tuition_policy_item tpi on tpi.id=b.policy_item_id
                  join tuition_policy tp on tp.id=tpi.tuition_policy_id
                  join admin_user issuer on issuer.id=b.issued_by
                 where b.id=:id
                """).param("id", billingId).query((row,index) -> new BillingDetail(
                new BillingRow(row.getObject("billing_id",UUID.class),row.getObject("student_id",UUID.class),
                        row.getString("student_name"),row.getString("year_month").trim(),row.getString("policy_label"),
                        "주 "+row.getInt("lesson_count_per_week")+"회 기본",row.getLong("billed_amount"),row.getLong("adjustment_amount"),
                        row.getLong("paid_amount"),row.getLong("refunded_amount"),row.getObject("due_date",LocalDate.class),
                        row.getString("payment_status"),row.getObject("issued_at",java.time.OffsetDateTime.class),row.getLong("version")),
                row.getString("student_status"),row.getObject("policy_item_id",UUID.class),row.getLong("monthly_amount"),
                row.getInt("default_due_day"),row.getObject("assignment_id",UUID.class),nullableLong(row,"override_amount"),
                row.getString("override_reason"),row.getLong("assignment_version"),row.getObject("issued_by",UUID.class),
                row.getString("issued_by_name"),row.getObject("billing_batch_id",UUID.class))).optional();
    }

    @Override
    public List<PreviewStudent> preview(YearMonth yearMonth) {
        LocalDate basisDate=yearMonth.atDay(1);
        return jdbc.sql("""
                select s.id student_id,s.student_name,
                       (select count(*)::int from student_tuition_assignment ax
                         where ax.student_id=s.id and ax.effective_from<=:basis_date
                           and (ax.effective_to is null or ax.effective_to>=:basis_date)) assignment_count,
                       assignment.id assignment_id,assignment.version assignment_version,
                       tpi.id policy_item_id,
                       tp.year::text || ' rev' || tp.revision || ' · 주 ' || tpi.lesson_count_per_week || '회' policy_label,
                       tp.status policy_status,coalesce(assignment.override_amount,tpi.monthly_amount) effective_amount,tp.default_due_day,
                       exists(select 1 from tuition_billing b where b.student_id=s.id and b.year_month=:year_month) already_issued
                  from student s
                  left join lateral (
                       select a.id,a.version,a.policy_item_id,a.override_amount
                         from student_tuition_assignment a
                        where a.student_id=s.id and a.effective_from<=:basis_date
                          and (a.effective_to is null or a.effective_to>=:basis_date)
                        order by a.effective_from desc,a.id
                        limit 1
                  ) assignment on true
                  left join tuition_policy_item tpi on tpi.id=assignment.policy_item_id
                  left join tuition_policy tp on tp.id=tpi.tuition_policy_id
                 where s.status='ACTIVE' and s.joined_at<=:basis_date
                 order by s.student_name_search,s.id
                """).param("basis_date",basisDate).param("year_month",yearMonth.toString())
                .query((row,index) -> new PreviewStudent(row.getObject("student_id",UUID.class),row.getString("student_name"),
                        row.getInt("assignment_count"),row.getObject("assignment_id",UUID.class),row.getLong("assignment_version"),
                        row.getObject("policy_item_id",UUID.class),row.getString("policy_label"),row.getString("policy_status"),nullableLong(row,"effective_amount"),
                        (Integer)row.getObject("default_due_day"),row.getBoolean("already_issued"))).list();
    }

    @Override
    public void savePreview(String scope,UUID token,String requestHash,byte[] encryptedResponse,java.time.OffsetDateTime expiresAt) {
        jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,response_status,encrypted_response,state,expires_at)
                values(:scope,:token,:hash,200,:encrypted_response,'COMPLETED',:expires_at)
                """).param("scope",scope).param("token",token).param("hash",requestHash)
                .param("encrypted_response",encryptedResponse).param("expires_at",expiresAt).update();
    }

    @Override
    public Optional<PreviewState> findPreview(String scope,UUID token,java.time.OffsetDateTime now) {
        return jdbc.sql("""
                select request_hash,encrypted_response,expires_at from idempotency_record
                 where scope=:scope and idempotency_key=:token and state='COMPLETED' and expires_at>:now
                """).param("scope",scope).param("token",token).param("now",now)
                .query((row,index)->new PreviewState(row.getString("request_hash"),row.getBytes("encrypted_response"),
                        row.getObject("expires_at",java.time.OffsetDateTime.class))).optional();
    }

    private static Long nullableLong(java.sql.ResultSet row,String column) throws java.sql.SQLException {
        java.math.BigDecimal value=row.getBigDecimal(column);
        return value==null?null:value.longValueExact();
    }
}
