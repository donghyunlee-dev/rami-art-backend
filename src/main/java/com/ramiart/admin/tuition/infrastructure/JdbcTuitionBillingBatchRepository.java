package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.tuition.application.TuitionBillingBatchRepository;
import com.ramiart.admin.tuition.application.TuitionBillingService.BillingException;
import com.ramiart.admin.tuition.application.TuitionBillingService.PreviewCandidateState;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTuitionBillingBatchRepository implements TuitionBillingBatchRepository {
    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    public JdbcTuitionBillingBatchRepository(JdbcClient jdbc,AuditRecorder audit) { this.jdbc=jdbc; this.audit=audit; }

    @Override public Claim claim(String scope,UUID key,String hash,String yearMonth,int requested,UUID actorId) {
        UUID newId=UUID.randomUUID();
        int inserted=jdbc.sql("""
                insert into tuition_billing_batch(id,year_month,requested_count,requested_by,idempotency_scope,idempotency_key,request_hash)
                values(:id,:month,:requested,:actor,:scope,:key,:hash) on conflict(idempotency_scope,idempotency_key) do nothing
                """).param("id",newId).param("month",yearMonth).param("requested",requested).param("actor",actorId)
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if(inserted==1) return new Claim(newId,"PROCESSING",hash,true);
        Claim existing=jdbc.sql("select id,status,request_hash from tuition_billing_batch where idempotency_scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key)
                .query((row,index)->new Claim(row.getObject("id",UUID.class),row.getString("status"),row.getString("request_hash"),false)).single();
        if(!hash.equals(existing.requestHash())) throw new BillingException("IDEMPOTENCY_KEY_REUSED");
        return existing;
    }

    @Override public Optional<Claim> findClaim(String scope,UUID key) {
        return jdbc.sql("select id,status,request_hash from tuition_billing_batch where idempotency_scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key)
                .query((row,index)->new Claim(row.getObject("id",UUID.class),row.getString("status"),row.getString("request_hash"),false)).optional();
    }

    @Override public Result issueOne(UUID batchId,String yearMonth,UUID actorId,PreviewCandidateState expected,
            String requestId,String ipAddress,String userAgent) {
        YearMonth month=YearMonth.parse(yearMonth); LocalDate basisDate=month.atDay(1);
        var student=jdbc.sql("select id from student where id=:id and status='ACTIVE' and joined_at<=:date for update")
                .param("id",expected.studentId()).param("date",basisDate)
                .query((row,index)->row.getObject("id",UUID.class)).optional();
        if(student.isEmpty()) throw new BillingException("BILLING_ASSIGNMENT_MISSING");
        var assignments=jdbc.sql("""
                select a.id,a.version,a.policy_item_id,a.override_amount,a.override_reason,tpi.monthly_amount,
                       tpi.lesson_count_per_week,tp.status policy_status,tp.default_due_day
                  from student_tuition_assignment a join tuition_policy_item tpi on tpi.id=a.policy_item_id
                  join tuition_policy tp on tp.id=tpi.tuition_policy_id
                 where a.student_id=:student and a.effective_from<=:date and (a.effective_to is null or a.effective_to>=:date)
                 order by a.effective_from desc,a.id for update of a
                """).param("student",expected.studentId()).param("date",basisDate)
                .query((row,index)->new CurrentAssignment(row.getObject("id",UUID.class),row.getLong("version"),
                        row.getObject("policy_item_id",UUID.class),row.getBigDecimal("override_amount"),row.getString("override_reason"),
                        row.getLong("monthly_amount"),row.getInt("lesson_count_per_week"),row.getString("policy_status"),row.getInt("default_due_day"))).list();
        if(assignments.isEmpty()) throw new BillingException("BILLING_ASSIGNMENT_MISSING");
        if(assignments.size()!=1) throw new BillingException("BILLING_ASSIGNMENT_CONFLICT");
        CurrentAssignment current=assignments.getFirst();
        if(!List.of("PUBLISHED","ARCHIVED").contains(current.policyStatus())) throw new BillingException("BILLING_ASSIGNMENT_MISSING");
        long amount=current.overrideAmount()==null?current.monthlyAmount():current.overrideAmount().longValueExact();
        LocalDate dueDate=month.atDay(Math.min(current.defaultDueDay(),month.lengthOfMonth()));
        if(!current.id().equals(expected.assignmentId())||current.version()!=expected.assignmentVersion()
                ||!current.policyItemId().equals(expected.policyItemId())||amount!=expected.amount()||!dueDate.equals(expected.dueDate()))
            throw new BillingException("BILLING_PREVIEW_CHANGED");

        Optional<Result> existing=findBilling(expected.studentId(),yearMonth);
        if(existing.isPresent()) {
            Result result=new Result(expected.studentId(),"EXISTING",existing.get().billingId(),existing.get().amount(),null);
            insertResult(batchId,result);
            return result;
        }
        UUID billingId=UUID.randomUUID();
        String paymentStatus=amount==0?"PAID":"ISSUED";
        jdbc.sql("""
                insert into tuition_billing(id,billing_batch_id,student_id,year_month,tuition_assignment_id,policy_item_id,
                    billed_amount,adjustment_amount,paid_amount,refunded_amount,due_date,payment_status,issued_by,
                    assignment_version,override_amount_snapshot,override_reason_snapshot,version)
                values(:id,:batch,:student,:month,:assignment,:item,:amount,0,0,0,:due,:status,:actor,
                    :assignment_version,:override_amount,:override_reason,0)
                """).param("id",billingId).param("batch",batchId).param("student",expected.studentId())
                .param("month",yearMonth).param("assignment",current.id()).param("item",current.policyItemId())
                .param("amount",amount).param("due",dueDate).param("status",paymentStatus).param("actor",actorId)
                .param("assignment_version",current.version()).param("override_amount",current.overrideAmount())
                .param("override_reason",current.overrideReason()).update();
        Result result=new Result(expected.studentId(),"CREATED",billingId,amount,null);
        insertResult(batchId,result);
        audit.record(new AuditRecorder.Event(java.time.Instant.now(),requestId, "MGT-TUITION-BILLING-GENERATE",
                "FINANCE","ADMIN",actorId,null,"TUITION_BILLING_ISSUED","TUITION_BILLING",billingId,
                "SUCCESS",null,ipAddress,userAgent,java.util.Map.of("yearMonth",yearMonth,"batchId",batchId.toString(),"amount",amount)));
        return result;
    }

    @Override public void recordFailure(UUID batchId,UUID studentId,String errorCode) {
        jdbc.sql("insert into tuition_billing_batch_result(id,billing_batch_id,student_id,result_status,error_code) values(:id,:batch,:student,'FAILED',:error) on conflict(billing_batch_id,student_id) do nothing")
                .param("id",UUID.randomUUID()).param("batch",batchId).param("student",studentId).param("error",errorCode).update();
    }

    private void insertResult(UUID batchId,Result result) {
        jdbc.sql("""
                insert into tuition_billing_batch_result(id,billing_batch_id,student_id,result_status,billing_id,amount)
                values(:id,:batch,:student,:status,:billing,:amount) on conflict(billing_batch_id,student_id) do nothing
                """).param("id",UUID.randomUUID()).param("batch",batchId).param("student",result.studentId())
                .param("status",result.status()).param("billing",result.billingId()).param("amount",result.amount()).update();
    }

    private Optional<Result> findBilling(UUID studentId,String month) {
        return jdbc.sql("select id,billed_amount from tuition_billing where student_id=:student and year_month=:month")
                .param("student",studentId).param("month",month).query((row,index)->new Result(studentId,"EXISTING",
                        row.getObject("id",UUID.class),row.getBigDecimal("billed_amount").longValueExact(),null)).optional();
    }

    @Override public void complete(UUID batchId) {
        jdbc.sql("""
                update tuition_billing_batch b set
                  created_count=x.created,existing_count=x.existing,failed_count=x.failed,created_amount=x.amount,
                  status=case when x.failed=0 then 'COMPLETED' when x.created+x.existing=0 then 'FAILED' else 'PARTIAL' end,
                  completed_at=statement_timestamp()
                from (select count(*) filter(where result_status='CREATED')::int created,
                             count(*) filter(where result_status='EXISTING')::int existing,
                             count(*) filter(where result_status='FAILED')::int failed,
                             coalesce(sum(amount) filter(where result_status='CREATED'),0)::numeric(14,0) amount
                        from tuition_billing_batch_result where billing_batch_id=:id) x
                where b.id=:id
                """).param("id",batchId).update();
    }

    @Override public Optional<Batch> find(UUID batchId) {
        var batch=jdbc.sql("select id,year_month,status,requested_count,created_count,existing_count,failed_count,created_amount from tuition_billing_batch where id=:id")
                .param("id",batchId).query((row,index)->new BatchHeader(row.getObject("id",UUID.class),row.getString("year_month").trim(),
                        row.getString("status"),row.getInt("requested_count"),row.getInt("created_count"),row.getInt("existing_count"),
                        row.getInt("failed_count"),row.getBigDecimal("created_amount").longValueExact())).optional();
        if(batch.isEmpty()) return Optional.empty();
        BatchHeader header=batch.get();
        List<Result> results=jdbc.sql("select student_id,result_status,billing_id,amount,error_code from tuition_billing_batch_result where billing_batch_id=:id order by student_id")
                .param("id",batchId).query((row,index)->new Result(row.getObject("student_id",UUID.class),row.getString("result_status"),
                        row.getObject("billing_id",UUID.class),row.getBigDecimal("amount")==null?null:row.getBigDecimal("amount").longValueExact(),row.getString("error_code"))).list();
        return Optional.of(new Batch(header.id(),header.month(),header.status(),header.requested(),header.created(),header.existing(),header.failed(),header.amount(),
                results.stream().filter(row->"CREATED".equals(row.status())).toList(),
                results.stream().filter(row->"EXISTING".equals(row.status())).toList(),
                results.stream().filter(row->"FAILED".equals(row.status())).toList()));
    }

    private record CurrentAssignment(UUID id,long version,UUID policyItemId,java.math.BigDecimal overrideAmount,
            String overrideReason,long monthlyAmount,int lessonCount,String policyStatus,int defaultDueDay) {}
    private record BatchHeader(UUID id,String month,String status,int requested,int created,int existing,int failed,long amount) {}
}
