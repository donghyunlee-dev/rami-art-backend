package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.tuition.application.TuitionPaymentRepository;
import com.ramiart.admin.tuition.application.TuitionPaymentService.TuitionPaymentException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTuitionPaymentRepository implements TuitionPaymentRepository {
    private final JdbcClient jdbc;
    public JdbcTuitionPaymentRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    @Override public Optional<Billing> lockBilling(UUID id) {
        return billingQuery(id," for update of b");
    }
    @Override public Optional<Billing> findBilling(UUID id) { return billingQuery(id,""); }
    private Optional<Billing> billingQuery(UUID id,String lock) {
        return jdbc.sql(("""
                select b.id,b.student_id,s.student_name,b.year_month,b.billed_amount,b.adjustment_amount,
                    b.paid_amount,b.refunded_amount,b.due_date,b.payment_status,b.version
                from tuition_billing b join student s on s.id=b.student_id where b.id=:id
                """)+lock).param("id",id).query((r,n)->new Billing(r.getObject("id",UUID.class),r.getObject("student_id",UUID.class),
                r.getString("student_name"),r.getString("year_month"),r.getLong("billed_amount"),r.getLong("adjustment_amount"),
                r.getLong("paid_amount"),r.getLong("refunded_amount"),r.getObject("due_date",LocalDate.class),r.getString("payment_status"),r.getLong("version"))).optional();
    }
    @Override public Optional<Payment> lockPayment(UUID id) {
        return paymentQuery("where p.id=:id for update of p",id,null,null,1).stream().findFirst();
    }
    @Override public Optional<Payment> find(UUID id) { return paymentQuery("where p.id=:id",id,null,null,1).stream().findFirst(); }
    @Override public List<Payment> list(UUID billingId,OffsetDateTime before,UUID beforeId,int size) {
        String cursor=before==null?"":"and (p.created_at,p.id)<(:before,:before_id)";
        var query=jdbc.sql("""
                select p.*,u.display_name created_by_name,cu.display_name cancelled_by_name,
                    coalesce(r.refunded_amount,0) refunded_amount,fe.id entry_id
                from tuition_payment p join admin_user u on u.id=p.created_by
                left join admin_user cu on cu.id=p.cancelled_by
                left join lateral (select sum(amount) refunded_amount from tuition_refund where payment_id=p.id and status='CONFIRMED') r on true
                left join financial_entry fe on fe.source_type='TUITION_PAYMENT' and fe.source_id=p.id
                where p.billing_id=:billing_id %s order by p.created_at desc,p.id desc limit :size
                """.formatted(cursor)).param("billing_id",billingId).param("size",size);
        if(before!=null) query=query.param("before",before).param("before_id",beforeId);
        return query.query((r,n)->mapPayment(r)).list();
    }
    private List<Payment> paymentQuery(String suffix,UUID id,OffsetDateTime before,UUID beforeId,int size) {
        var query=jdbc.sql("""
                select p.*,u.display_name created_by_name,cu.display_name cancelled_by_name,
                    coalesce(r.refunded_amount,0) refunded_amount,fe.id entry_id
                from tuition_payment p join admin_user u on u.id=p.created_by left join admin_user cu on cu.id=p.cancelled_by
                left join lateral (select sum(amount) refunded_amount from tuition_refund where payment_id=p.id and status='CONFIRMED') r on true
                left join financial_entry fe on fe.source_type='TUITION_PAYMENT' and fe.source_id=p.id %s
                """.formatted(suffix));
        if(id!=null) query=query.param("id",id);
        return query.query((r,n)->mapPayment(r)).list();
    }
    private static Payment mapPayment(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Payment(r.getObject("id",UUID.class),r.getObject("billing_id",UUID.class),r.getObject("paid_on",LocalDate.class),
                r.getLong("amount"),r.getString("method"),r.getString("memo"),r.getString("status"),r.getObject("created_by",UUID.class),
                r.getString("created_by_name"),r.getObject("created_at",OffsetDateTime.class),r.getObject("cancelled_by",UUID.class),
                r.getString("cancelled_by_name"),r.getObject("cancelled_at",OffsetDateTime.class),r.getString("cancel_reason"),r.getLong("version"),
                r.getLong("refunded_amount"),r.getObject("entry_id",UUID.class));
    }
    @Override public void insert(UUID billingId,UUID paymentId,UUID entryId,UUID actorId,LocalDate paidOn,long amount,
            String method,String memo,long paidAmount,String paymentStatus,long billingVersion) {
        int changed=jdbc.sql("""
                update tuition_billing set paid_amount=:paid,payment_status=:status,version=version+1
                where id=:id and version=:version
                """).param("paid",paidAmount).param("status",paymentStatus).param("id",billingId).param("version",billingVersion).update();
        if(changed!=1) throw new TuitionPaymentException("BILLING_VERSION_CONFLICT");
        jdbc.sql("insert into tuition_payment(id,billing_id,paid_on,amount,method,memo,status,created_by) values(:id,:billing,:date,:amount,:method,:memo,'CONFIRMED',:actor)")
                .param("id",paymentId).param("billing",billingId).param("date",paidOn).param("amount",amount).param("method",method).param("memo",memo).param("actor",actorId).update();
        String accountType="CASH".equals(method)?"CASH":"CARD".equals(method)?"CARD":"BANK";
        UUID account=jdbc.sql("select id from finance_account where active and type=:type order by display_order,id limit 1")
                .param("type",accountType).query(UUID.class).optional().orElseThrow(()->new TuitionPaymentException("PAYMENT_SAVE_FAILED"));
        UUID entry=entryId;
        jdbc.sql("insert into financial_entry(id,transaction_date,type,account_id,category_code,description,amount,status,source_type,source_id,created_by) values(:id,:date,'INCOME',:account,'TUITION','수업료 납입',:amount,'CONFIRMED','TUITION_PAYMENT',:source,:actor)")
                .param("id",entry).param("date",paidOn).param("account",account).param("amount",amount).param("source",paymentId).param("actor",actorId).update();
    }
    @Override public void cancel(UUID paymentId,UUID entryId,UUID actorId,String reason,long paidAmount,String paymentStatus,long paymentVersion,long billingVersion) {
        Payment payment=lockPayment(paymentId).orElseThrow(()->new TuitionPaymentException("TUITION_PAYMENT_NOT_FOUND"));
        Billing billing=lockBilling(payment.billingId()).orElseThrow(()->new TuitionPaymentException("TUITION_BILLING_NOT_FOUND"));
        if(payment.version()!=paymentVersion) throw new TuitionPaymentException("PAYMENT_VERSION_CONFLICT");
        if(billing.version()!=billingVersion) throw new TuitionPaymentException("BILLING_VERSION_CONFLICT");
        if(!"CONFIRMED".equals(payment.status())) throw new TuitionPaymentException("PAYMENT_ALREADY_CANCELLED");
        if(payment.refundedAmount()>0) throw new TuitionPaymentException("TUITION_PAYMENT_HAS_REFUND");
        jdbc.sql("update tuition_payment set status='CANCELLED',cancelled_by=:actor,cancelled_at=statement_timestamp(),cancel_reason=:reason,version=version+1 where id=:id")
                .param("actor",actorId).param("reason",reason).param("id",paymentId).update();
        jdbc.sql("update financial_entry set status='CANCELLED',cancelled_by=:actor,cancelled_at=statement_timestamp(),cancel_reason=:reason,version=version+1 where id=:id and status='CONFIRMED'")
                .param("actor",actorId).param("reason",reason).param("id",entryId).update();
        jdbc.sql("update tuition_billing set paid_amount=:paid,payment_status=:status,version=version+1 where id=:id")
                .param("paid",paidAmount).param("status",paymentStatus).param("id",billing.id()).update();
    }
    @Override public Claim claim(String scope,UUID key,String hash) {
        int inserted=jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing")
                    .param("scope",scope).param("key",key).param("hash",hash).update();
        if(inserted==1) {
            return new Claim(true,"PROCESSING",null,hash);
        }
        Claim old=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update")
                .param("scope",scope).param("key",key).query((r,n)->new Claim(false,r.getString("state"),r.getObject("resource_id",UUID.class),r.getString("request_hash"))).single();
        if(!hash.equals(old.requestHash())) throw new TuitionPaymentException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(old.status())) throw new TuitionPaymentException("IDEMPOTENCY_IN_PROGRESS");
        return old;
    }
    @Override public void complete(String scope,UUID key,UUID id,int status) {
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
                .param("id",id).param("status",status).param("scope",scope).param("key",key).update();
    }
    @Override public Optional<UUID> findEntry(UUID paymentId) {
        return jdbc.sql("select id from financial_entry where source_type='TUITION_PAYMENT' and source_id=:payment").param("payment",paymentId).query(UUID.class).optional();
    }
}
