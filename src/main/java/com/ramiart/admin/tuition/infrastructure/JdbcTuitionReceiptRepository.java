package com.ramiart.admin.tuition.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.tuition.application.TuitionReceiptRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTuitionReceiptRepository implements TuitionReceiptRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public JdbcTuitionReceiptRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override public Optional<Source> lockSource(UUID id){return sourceQuery(id,true);}
    @Override public Optional<Source> source(UUID id){return sourceQuery(id,false);}
    private Optional<Source> sourceQuery(UUID id,boolean lock){
        String sql="""
            select p.id payment_id,p.billing_id,b.year_month,s.student_name,p.paid_on,p.amount,p.method,p.status payment_status,
                p.version payment_version,b.version billing_version,
                coalesce(r.amount,0) refund_amount,
                coalesce((select studio_name from studio_profile where status='PUBLISHED' limit 1),'라미아트미술교습소') studio_name
            from tuition_payment p join tuition_billing b on b.id=p.billing_id join student s on s.id=b.student_id
            left join lateral(select sum(amount) amount from tuition_refund where payment_id=p.id and status='CONFIRMED') r on true
            where p.id=:id
            """+(lock?" for update of p,b":"");
        return jdbc.sql(sql).param("id",id).query((r,n)->new Source(r.getObject("payment_id",UUID.class),r.getObject("billing_id",UUID.class),
                r.getString("year_month"),r.getString("student_name"),r.getObject("paid_on",java.time.LocalDate.class),r.getLong("amount"),r.getString("method"),r.getString("payment_status"),
                r.getLong("payment_version"),r.getLong("billing_version"),r.getLong("refund_amount"),r.getString("studio_name"))).optional();
    }
    @Override public Optional<Receipt> byPayment(UUID id){return receiptQuery("where payment_id=:id",id,false);}
    @Override public Optional<Receipt> lockReceipt(UUID id){return receiptQuery("where id=:id",id,true);}
    private Optional<Receipt> receiptQuery(String where,UUID id,boolean lock){return jdbc.sql("select id,payment_id,receipt_number,current_version,status from tuition_receipt "+where+(lock?" for update":""))
            .param("id",id).query((r,n)->new Receipt(r.getObject("id",UUID.class),r.getObject("payment_id",UUID.class),r.getString("receipt_number"),r.getInt("current_version"),r.getString("status"))).optional();}
    @Override public List<Version> versions(UUID id){return jdbc.sql("select * from tuition_receipt_version where receipt_id=:id order by version desc")
            .param("id",id).query((r,n)->versionRow(r.getObject("id",UUID.class),r.getObject("receipt_id",UUID.class),r.getInt("version"),r.getString("status"),
                    r.getString("snapshot"),r.getLong("refund_amount"),r.getString("storage_key"),r.getString("sha256"),r.getObject("file_size",Long.class),
                    r.getString("issue_reason"),r.getObject("issued_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class))).list();}
    @Override public Optional<Version> version(UUID receiptId,int number){return jdbc.sql("select * from tuition_receipt_version where receipt_id=:id and version=:version")
            .param("id",receiptId).param("version",number).query((r,n)->versionRow(r.getObject("id",UUID.class),r.getObject("receipt_id",UUID.class),r.getInt("version"),r.getString("status"),
                    r.getString("snapshot"),r.getLong("refund_amount"),r.getString("storage_key"),r.getString("sha256"),r.getObject("file_size",Long.class),
                    r.getString("issue_reason"),r.getObject("issued_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class))).optional();}
    private Version versionRow(UUID id,UUID receipt,int number,String status,String snapshot,long refund,String key,String sha,Long size,String reason,
            UUID actor,OffsetDateTime created,OffsetDateTime completed){
        try{return new Version(id,receipt,number,status,mapper.readValue(snapshot,new TypeReference<Map<String,Object>>(){}),refund,key,sha,size,reason,actor,
                created.toInstant(),completed==null?null:completed.toInstant());}catch(Exception e){throw new TuitionReceiptException("RECEIPT_FILE_INTEGRITY_FAILED",e);}}
    @Override public String nextReceiptNumber(String yearMonth){long sequence=jdbc.sql("select nextval('tuition_receipt_number_seq')").query(Long.class).single();return "R-"+yearMonth.replace("-","")+"-%08d".formatted(sequence);}
    @Override public void create(UUID receipt,UUID payment,String number,UUID actor,UUID versionId,int version,Map<String,Object> snapshot,long refund,String reason){
        jdbc.sql("insert into tuition_receipt(id,payment_id,receipt_number,current_version,status,created_by) values(:id,:payment,:number,1,'ACTIVE',:actor) on conflict(payment_id) do nothing")
                .param("id",receipt).param("payment",payment).param("number",number).param("actor",actor).update();
        if(byPayment(payment).map(Receipt::id).filter(receipt::equals).isEmpty())return;
        insertVersion(receipt,versionId,version,actor,snapshot,refund,reason);
    }
    @Override public void beginReissue(UUID receipt,int expected,int next,UUID versionId,UUID actor,Map<String,Object> snapshot,long refund,String reason){
        int updated=jdbc.sql("update tuition_receipt set current_version=:next where id=:id and current_version=:expected and status='ACTIVE'")
                .param("next",next).param("id",receipt).param("expected",expected).update();
        if(updated!=1)throw new TuitionReceiptException("RECEIPT_VERSION_CONFLICT");
        insertVersion(receipt,versionId,next,actor,snapshot,refund,reason);
    }
    private void insertVersion(UUID receipt,UUID id,int number,UUID actor,Map<String,Object> snapshot,long refund,String reason){
        try{jdbc.sql("insert into tuition_receipt_version(id,receipt_id,version,status,snapshot,refund_amount,issue_reason,issued_by) values(:id,:receipt,:version,'GENERATING',cast(:snapshot as jsonb),:refund,:reason,:actor)")
                .param("id",id).param("receipt",receipt).param("version",number).param("snapshot",mapper.writeValueAsString(snapshot)).param("refund",refund)
                .param("reason",reason).param("actor",actor).update();}catch(java.io.IOException e){throw new TuitionReceiptException("RECEIPT_GENERATION_FAILED",e);}
    }
    @Override public void markReady(UUID id,String key,String sha,long size){int changed=jdbc.sql("update tuition_receipt_version set status='READY',storage_key=:key,sha256=:sha,file_size=:size,completed_at=statement_timestamp() where id=:id and status='GENERATING'")
            .param("key",key).param("sha",sha).param("size",size).param("id",id).update();if(changed!=1)throw new TuitionReceiptException("RECEIPT_GENERATION_FAILED");}
    @Override public void markFailed(UUID id){jdbc.sql("update tuition_receipt_version set status='FAILED',completed_at=statement_timestamp() where id=:id and status='GENERATING'").param("id",id).update();}
    @Override public Claim claim(String scope,UUID key,String hash){
        int inserted=jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();if(inserted==1)return new Claim(true,hash,"PROCESSING",null);
        Claim prior=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update")
                .param("scope",scope).param("key",key).query((r,n)->new Claim(false,r.getString("request_hash"),r.getString("state"),r.getObject("resource_id",UUID.class))).single();
        if(!hash.equals(prior.requestHash()))throw new TuitionReceiptException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(prior.state()))throw new TuitionReceiptException("IDEMPOTENCY_IN_PROGRESS");return prior;
    }
    @Override public void complete(String scope,UUID key,UUID resource,int status){jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
            .param("id",resource).param("status",status).param("scope",scope).param("key",key).update();}
    @Override public void voidForPayment(UUID paymentId){jdbc.sql("update tuition_receipt set status='VOID' where payment_id=:id and status='ACTIVE'").param("id",paymentId).update();}
    public static final class TuitionReceiptException extends RuntimeException {private final String code;public TuitionReceiptException(String code){super(code);this.code=code;}public TuitionReceiptException(String code,Throwable cause){super(code,cause);this.code=code;}public String code(){return code;}}
}
