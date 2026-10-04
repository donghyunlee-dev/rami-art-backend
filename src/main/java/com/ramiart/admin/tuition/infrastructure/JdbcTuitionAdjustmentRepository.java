package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.tuition.application.TuitionAdjustmentRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTuitionAdjustmentRepository implements TuitionAdjustmentRepository {
    private final JdbcClient jdbc;
    public JdbcTuitionAdjustmentRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    @Override public Optional<Billing> findBilling(UUID id){return billing(id,false);}
    @Override public Optional<Billing> lockBilling(UUID id){return billing(id,true);}
    private Optional<Billing> billing(UUID id,boolean lock){
        return jdbc.sql("""
                select b.id,b.student_id,s.student_name,b.year_month,b.billed_amount,b.adjustment_amount,
                    b.paid_amount,b.refunded_amount,b.due_date,b.payment_status,b.version
                from tuition_billing b join student s on s.id=b.student_id where b.id=:id
                """+(lock?" for update of b":"")).param("id",id).query((r,n)->new Billing(r.getObject("id",UUID.class),
                r.getObject("student_id",UUID.class),r.getString("student_name"),r.getString("year_month"),r.getLong("billed_amount"),
                r.getLong("adjustment_amount"),r.getLong("paid_amount"),r.getLong("refunded_amount"),r.getObject("due_date",LocalDate.class),
                r.getString("payment_status"),r.getLong("version"))).optional();
    }
    @Override public List<Adjustment> adjustments(UUID billingId){return jdbc.sql("""
            select * from billing_adjustment where billing_id=:id order by created_at desc,id desc
            """).param("id",billingId).query((r,n)->new Adjustment(r.getObject("id",UUID.class),r.getObject("billing_id",UUID.class),
            r.getString("type"),r.getLong("signed_amount"),r.getString("reason"),r.getString("status"),r.getObject("created_by",UUID.class),
            r.getObject("created_at",OffsetDateTime.class),r.getObject("cancelled_by",UUID.class),r.getObject("cancelled_at",OffsetDateTime.class),
            r.getString("cancel_reason"),r.getLong("version"))).list();}
    @Override public List<Refund> refunds(UUID billingId){return jdbc.sql("""
            select * from tuition_refund where billing_id=:id order by refunded_on desc,id desc
            """).param("id",billingId).query((r,n)->refund(r)).list();}
    @Override public Optional<Adjustment> lockAdjustment(UUID id){return jdbc.sql("select * from billing_adjustment where id=:id for update")
            .param("id",id).query((r,n)->new Adjustment(r.getObject("id",UUID.class),r.getObject("billing_id",UUID.class),
            r.getString("type"),r.getLong("signed_amount"),r.getString("reason"),r.getString("status"),r.getObject("created_by",UUID.class),
            r.getObject("created_at",OffsetDateTime.class),r.getObject("cancelled_by",UUID.class),r.getObject("cancelled_at",OffsetDateTime.class),
            r.getString("cancel_reason"),r.getLong("version"))).optional();}
    @Override public Optional<Refund> lockRefund(UUID id){return jdbc.sql("select * from tuition_refund where id=:id for update")
            .param("id",id).query((r,n)->refund(r)).optional();}
    private static Refund refund(java.sql.ResultSet r)throws java.sql.SQLException{return new Refund(r.getObject("id",UUID.class),r.getObject("billing_id",UUID.class),
            r.getObject("payment_id",UUID.class),r.getObject("refunded_on",LocalDate.class),r.getLong("amount"),r.getString("method"),r.getString("reason"),
            r.getString("status"),r.getObject("financial_entry_id",UUID.class),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),
            r.getObject("cancelled_by",UUID.class),r.getObject("cancelled_at",OffsetDateTime.class),r.getString("cancel_reason"),r.getLong("version"));}
    @Override public Optional<Long> paymentVersion(UUID paymentId,UUID billingId){return jdbc.sql("select version from tuition_payment where id=:id and billing_id=:billing and status='CONFIRMED'")
            .param("id",paymentId).param("billing",billingId).query(Long.class).optional();}
    @Override public long paymentRefundable(UUID paymentId){return jdbc.sql("""
            select p.amount-coalesce(sum(r.amount) filter(where r.status='CONFIRMED'),0)
            from tuition_payment p left join tuition_refund r on r.payment_id=p.id where p.id=:id group by p.id,p.amount
            """).param("id",paymentId).query(Long.class).optional().orElse(0L);}
    @Override public void insertAdjustment(UUID id,UUID billingId,String type,long amount,String reason,UUID actor,long expectedVersion,
            long nextAdjustmentAmount,long nextPaymentAmount,long nextRefundAmount,String nextStatus){
        updateBilling(billingId,expectedVersion,nextAdjustmentAmount,nextPaymentAmount,nextRefundAmount,nextStatus);
        jdbc.sql("insert into billing_adjustment(id,billing_id,type,signed_amount,reason,created_by) values(:id,:billing,:type,:amount,:reason,:actor)")
                .param("id",id).param("billing",billingId).param("type",type).param("amount",amount).param("reason",reason).param("actor",actor).update();
    }
    @Override public void cancelAdjustment(UUID id,UUID actor,String reason,long targetVersion,long billingVersion,
            long nextAdjustmentAmount,long nextPaymentAmount,long nextRefundAmount,String nextStatus){
        int changed=jdbc.sql("update billing_adjustment set status='CANCELLED',cancelled_by=:actor,cancelled_at=statement_timestamp(),cancel_reason=:reason,version=version+1 where id=:id and version=:version and status='CONFIRMED'")
                .param("actor",actor).param("reason",reason).param("id",id).param("version",targetVersion).update();
        if(changed!=1)throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
        updateBillingFromVersion(id,billingVersion,nextAdjustmentAmount,nextPaymentAmount,nextRefundAmount,nextStatus);
    }
    @Override public void insertRefund(UUID id,UUID entryId,UUID billingId,UUID paymentId,LocalDate date,long amount,String method,
            String reason,UUID actor,long expectedBillingVersion,long nextRefundAmount,long nextPaymentAmount,String nextStatus){
        Billing billing=lockBilling(billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        updateBilling(billingId,expectedBillingVersion,billing.adjustmentAmount(),nextPaymentAmount,nextRefundAmount,nextStatus);
        String accountType="CASH".equals(method)?"CASH":"CARD".equals(method)?"CARD":"BANK";
        UUID account=jdbc.sql("select id from finance_account where active and type=:type order by display_order,id limit 1")
                .param("type",accountType).query(UUID.class).optional().orElseThrow(()->new TuitionAdjustmentException("TUITION_ADJUSTMENT_SAVE_FAILED"));
        jdbc.sql("insert into financial_entry(id,transaction_date,type,account_id,category_code,description,amount,status,source_type,source_id,created_by) values(:id,:date,'EXPENSE',:account,'TUITION_REFUND','수업료 환불',:amount,'CONFIRMED','TUITION_REFUND',:source,:actor)")
                .param("id",entryId).param("date",date).param("account",account).param("amount",amount).param("source",id).param("actor",actor).update();
        jdbc.sql("insert into tuition_refund(id,billing_id,payment_id,refunded_on,amount,method,reason,status,financial_entry_id,created_by) values(:id,:billing,:payment,:date,:amount,:method,:reason,'CONFIRMED',:entry,:actor)")
                .param("id",id).param("billing",billingId).param("payment",paymentId).param("date",date).param("amount",amount)
                .param("method",method).param("reason",reason).param("entry",entryId).param("actor",actor).update();
    }
    @Override public void cancelRefund(UUID id,UUID actor,String reason,long targetVersion,long billingVersion,
            long nextRefundAmount,long nextPaymentAmount,String nextStatus){
        Refund refund=lockRefund(id).orElseThrow(()->new TuitionAdjustmentException("TUITION_REFUND_NOT_FOUND"));
        Billing billing=lockBilling(refund.billingId()).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        int changed=jdbc.sql("update tuition_refund set status='CANCELLED',cancelled_by=:actor,cancelled_at=statement_timestamp(),cancel_reason=:reason,version=version+1 where id=:id and version=:version and status='CONFIRMED'")
                .param("actor",actor).param("reason",reason).param("id",id).param("version",targetVersion).update();
        if(changed!=1)throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
        int entryChanged=jdbc.sql("update financial_entry set status='CANCELLED',cancelled_by=:actor,cancelled_at=statement_timestamp(),cancel_reason=:reason,version=version+1 where id=:id and status='CONFIRMED'")
                .param("actor",actor).param("reason",reason).param("id",refund.entryId()).update();
        if(entryChanged!=1)throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_SAVE_FAILED");
        updateBilling(refund.billingId(),billingVersion,billing.adjustmentAmount(),nextPaymentAmount,nextRefundAmount,nextStatus);
    }
    private void updateBillingFromVersion(UUID adjustmentId,long version,long adjustment,long paid,long refunded,String status){
        UUID billingId=jdbc.sql("select billing_id from billing_adjustment where id=:id").param("id",adjustmentId).query(UUID.class).single();
        updateBilling(billingId,version,adjustment,paid,refunded,status);
    }
    private void updateBilling(UUID id,long version,long adjustment,long paid,long refunded,String status){
        int changed=jdbc.sql("update tuition_billing set adjustment_amount=:adjustment,paid_amount=:paid,refunded_amount=:refunded,payment_status=:status,version=version+1 where id=:id and version=:version")
                .param("adjustment",adjustment).param("paid",paid).param("refunded",refunded).param("status",status).param("id",id).param("version",version).update();
        if(changed!=1)throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
    }
    @Override public Claim claim(String scope,UUID key,String hash){
        int inserted=jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if(inserted==1)return new Claim(true,hash,"PROCESSING",null);
        Claim existing=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update")
                .param("scope",scope).param("key",key).query((r,n)->new Claim(false,r.getString("request_hash"),r.getString("state"),r.getObject("resource_id",UUID.class))).single();
        if(!hash.equals(existing.requestHash()))throw new TuitionAdjustmentException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(existing.state()))throw new TuitionAdjustmentException("IDEMPOTENCY_IN_PROGRESS");
        return existing;
    }
    @Override public void complete(String scope,UUID key,UUID id,int status){jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
            .param("id",id).param("status",status).param("scope",scope).param("key",key).update();}
    public static final class TuitionAdjustmentException extends RuntimeException {private final String code;public TuitionAdjustmentException(String code){super(code);this.code=code;}public String code(){return code;}}
}
