package com.ramiart.admin.retention.infrastructure;

import static com.ramiart.admin.retention.application.RetentionModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.consent.application.ConsentEvidenceStorage;
import com.ramiart.admin.datatransfer.application.DataTransferStorage;
import com.ramiart.admin.retention.application.RetentionRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRetentionRepository implements RetentionRepository {
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final DataTransferStorage transferStorage;
    private final ConsentEvidenceStorage evidenceStorage;
    public JdbcRetentionRepository(JdbcClient jdbc,DataSource dataSource,ObjectMapper mapper,DataTransferStorage transferStorage,ConsentEvidenceStorage evidenceStorage){this.jdbc=jdbc;this.dataSource=dataSource;this.mapper=mapper;this.transferStorage=transferStorage;this.evidenceStorage=evidenceStorage;}
    @Override public void lockGovernance(Instant now){
        jdbc.sql("select pg_advisory_xact_lock(hashtext('retention-governance'))").query((r,n)->true).single();
        jdbc.sql("update retention_hold set status='EXPIRED' where status='ACTIVE' and ends_at<=:now").param("now",ts(now)).update();
    }
    private static final Map<String,String> TABLES=Map.of("INQUIRY","inquiry","STUDENT","student","STUDENT_CONSENT","student_consent","DATA_TRANSFER_JOB","data_transfer_job","NOTIFICATION_MESSAGE","notification_message");
    @Override public boolean targetExists(String type,UUID id){String table=TABLES.get(type);return table!=null&&jdbc.sql("select exists(select 1 from "+table+" where id=:id)").param("id",id).query(Boolean.class).single();}
    @Override public boolean owner(UUID actor){return jdbc.sql("select exists(select 1 from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id where u.id=:actor and u.status='ACTIVE' and r.code='OWNER')").param("actor",actor).query(Boolean.class).single();}
    @Override public Hold createHold(HoldWrite w,UUID actor,Instant now){
        if(jdbc.sql("select exists(select 1 from retention_hold where target_type=:type and target_id=:id and status='ACTIVE')").param("type",w.targetType()).param("id",w.targetId()).query(Boolean.class).single())throw new RetentionException("RETENTION_HOLD_EXISTS");
        UUID id=UUID.randomUUID();jdbc.sql("insert into retention_hold(id,target_type,target_id,reason,starts_at,ends_at,created_by) values(:id,:type,:target,:reason,:now,:ends,:actor)")
                .param("id",id).param("type",w.targetType()).param("target",w.targetId()).param("reason",w.reason().trim()).param("now",ts(now)).param("ends",ts(w.endsAt())).param("actor",actor).update();
        return hold(id);
    }
    @Override public Hold releaseHold(UUID id,String reason,UUID actor,Instant now){
        if(jdbc.sql("update retention_hold set status='RELEASED',released_at=:now,release_reason=:reason,released_by=:actor where id=:id and status='ACTIVE'").param("now",ts(now)).param("reason",reason).param("actor",actor).param("id",id).update()!=1)throw new RetentionException("RETENTION_HOLD_NOT_ACTIVE");return hold(id);
    }
    private Hold hold(UUID id){return jdbc.sql("select * from retention_hold where id=:id").param("id",id).query(HOLD).single();}
    private static final RowMapper<Hold> HOLD=(r,n)->new Hold(r.getObject("id",UUID.class),r.getString("target_type"),r.getObject("target_id",UUID.class),r.getString("reason"),r.getString("status"),instant(r,"starts_at"),instant(r,"ends_at"),instant(r,"released_at"),r.getString("release_reason"),r.getObject("created_by",UUID.class),r.getObject("released_by",UUID.class),instant(r,"created_at"));
    @Override public List<Hold> holds(String type,String status,UUID after,int limit){return jdbc.sql("select * from retention_hold where (:type is null or target_type=:type) and (:status is null or status=:status) and (cast(:after as uuid) is null or id>cast(:after as uuid)) order by id limit :limit")
            .param("type",type,Types.VARCHAR).param("status",status,Types.VARCHAR).param("after",after,Types.OTHER).param("limit",limit).query(HOLD).list();}
    @Override public List<Candidate> candidates(String domain,Instant cutoff,Instant snapshotAt,Instant now){
        Definition d=definition(domain);
        String held="exists(select 1 from retention_hold h where h.status='ACTIVE' and h.starts_at<=:now and (h.ends_at is null or h.ends_at>:now) and ((h.target_type='"+d.type()+"' and h.target_id=x.id)"+d.parentHold()+"))";
        String created="INQUIRY".equals(domain)?"x.received_at":"x.created_at";
        String sql="select x.id,"+held+" held,("+d.reference()+") referenced,md5(to_jsonb(x)::text) version,"+d.storage()+" storage_key from "+d.table()+" x where "+d.eligibility()+" and "+created+"<=:snapshot order by x.id limit 10001";
        return jdbc.sql(sql).param("now",ts(now)).param("cutoff",ts(cutoff)).param("snapshot",ts(snapshotAt)).query((r,n)->new Candidate(r.getObject("id",UUID.class),r.getBoolean("held"),r.getBoolean("referenced"),r.getString("version"),r.getString("storage_key"))).list();
    }
    private static Definition definition(String domain){return switch(domain){
        case "INQUIRY"->new Definition("inquiry","INQUIRY","x.retention_expires_at<=:cutoff","exists(select 1 from enrollment_case e where e.inquiry_id=x.id)","null::text","");
        case "STUDENT_PRIVATE"->new Definition("student","STUDENT","x.private_purged_at is null and x.status in ('GRADUATED','DROPPED') and (select max(h.effective_date) from student_status_history h where h.student_id=x.id)+interval '5 years'<=:cutoff",
                "exists(select 1 from tuition_billing b where b.student_id=x.id) or exists(select 1 from attendance_session_student a where a.student_id=x.id) or exists(select 1 from student_lesson_record l where l.student_id=x.id) or exists(select 1 from student_note n where n.student_id=x.id) or exists(select 1 from enrollment_case e where e.student_id=x.id) or exists(select 1 from audit_log a where a.target_type='STUDENT' and a.target_id=x.id)","null::text","");
        case "CONSENT_EVIDENCE"->new Definition("student_consent","STUDENT_CONSENT","x.evidence_asset_id is not null and x.evidence_purged_at is null and x.status in ('REVOKED','EXPIRED') and coalesce(x.revoked_at,x.expires_on::timestamptz)+interval '5 years'<=:cutoff",
                "exists(select 1 from media_asset_reference r where r.asset_id=x.evidence_asset_id) or exists(select 1 from student_consent c where c.evidence_asset_id=x.evidence_asset_id and c.id<>x.id)","(select a.storage_key from media_asset a where a.id=x.evidence_asset_id)"," or (h.target_type='STUDENT' and h.target_id=x.student_id)");
        case "TRANSFER_FILE"->new Definition("data_transfer_job","DATA_TRANSFER_JOB","x.expires_at<=:cutoff and x.status<>'EXPIRED'","false","x.storage_key","");
        case "NOTIFICATION_PAYLOAD"->new Definition("notification_message","NOTIFICATION_MESSAGE","x.payload_purged_at is null and x.status in ('SENT','FAILED','CANCELLED') and x.next_attempt_at is null and greatest(x.created_at,x.sent_at,x.cancelled_at,x.last_attempt_at)+interval '1 year'<=:cutoff","false","null::text"," or (h.target_type='STUDENT' and h.target_id=x.student_id)");
        default->throw new RetentionException("VALIDATION_ERROR");};}
    private record Definition(String table,String type,String eligibility,String reference,String storage,String parentHold){}
    @Override public StoredRun createPreview(String domain,String policy,Instant cutoff,List<Candidate> candidates,String digest,Instant now){
        UUID id=UUID.randomUUID(),preview=UUID.randomUUID();int held=(int)candidates.stream().filter(Candidate::held).count();int referenced=(int)candidates.stream().filter(c->!c.held()&&c.referenced()).count();
        jdbc.sql("insert into retention_run(id,domain,policy_version,cutoff_at,preview_version,candidate_count,hold_excluded_count,reference_excluded_count,cursor,created_at) values(:id,:domain,:policy,:cutoff,:preview,:count,:held,:referenced,:digest,:now)")
            .param("id",id).param("domain",domain).param("policy",policy).param("cutoff",ts(cutoff)).param("preview",preview).param("count",candidates.size()).param("held",held).param("referenced",referenced).param("digest",digest).param("now",ts(now)).update();return findRun(id,false).orElseThrow();
    }
    @Override public Optional<StoredRun> findRun(UUID id,boolean lock){return jdbc.sql("select * from retention_run where id=:id"+(lock?" for update":"")).param("id",id).query(this::stored).optional();}
    @Override public Optional<StoredRun> findPreview(UUID preview,boolean lock){return jdbc.sql("select * from retention_run where preview_version=:preview"+(lock?" for update":"")).param("preview",preview).query(this::stored).optional();}
    @Override public Optional<StoredRun> latest(String domain){return jdbc.sql("select * from retention_run where domain=:domain and status not in ('PREVIEWED','STALE') order by created_at desc,id desc limit 1").param("domain",domain).query(this::stored).optional();}
    @Override public Optional<StoredRun> claimNext(){return jdbc.sql("select * from retention_run where status='PROCESSING' order by started_at,id limit 1 for update skip locked").query(this::stored).optional();}
    @Override public boolean processing(String domain){return jdbc.sql("select exists(select 1 from retention_run where domain=:domain and status='PROCESSING')").param("domain",domain).query(Boolean.class).single();}
    private StoredRun stored(ResultSet r,int n) throws SQLException {
        Map<String,Integer> failures;try{failures=mapper.readValue(r.getString("failure_summary"),new TypeReference<>(){});}catch(Exception e){throw new SQLException("Invalid retention aggregate",e);}
        Run value=new Run(r.getObject("id",UUID.class),r.getObject("preview_version",UUID.class),r.getString("domain"),r.getString("status"),r.getInt("candidate_count"),r.getInt("hold_excluded_count"),r.getInt("reference_excluded_count"),r.getInt("processed_count"),r.getInt("failed_count"),failures,instant(r,"created_at"),instant(r,"approved_at"),instant(r,"started_at"),instant(r,"completed_at"));
        return new StoredRun(value,r.getString("policy_version"),instant(r,"cutoff_at"),r.getString("cursor"),r.getObject("approved_by",UUID.class));
    }
    @Override public Optional<UUID> replay(UUID actor,UUID key,String hash){
        return jdbc.sql("select request_hash,resource_id from idempotency_record where scope=:scope and idempotency_key=:key").param("scope","RETENTION_RUN:"+actor).param("key",key).query((r,n)->{if(!hash.equals(r.getString(1)))throw new RetentionException("IDEMPOTENCY_KEY_REUSED");return r.getObject(2,UUID.class);}).optional();
    }
    @Override public void queue(StoredRun stored,UUID actor,UUID key,String hash,Instant now){
        UUID id=stored.value().id();jdbc.sql("update retention_run set status='PROCESSING',approved_by=:actor,approved_at=:now,started_at=:now,completed_at=null,failed_count=0,failure_summary='{}' where id=:id").param("actor",actor).param("now",ts(now)).param("id",id).update();
        jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,resource_id,response_status,expires_at) values(:scope,:key,:hash,'COMPLETED',:id,202,statement_timestamp()+interval '24 hours')").param("scope","RETENTION_RUN:"+actor).param("key",key).param("hash",hash).param("id",id).update();
    }
    @Override public void purge(String domain,Candidate c,Instant now){
        Connection connection=DataSourceUtils.getConnection(dataSource);Savepoint savepoint=null;
        try{
            savepoint=connection.setSavepoint();Definition d=definition(domain);
            if("CONSENT_EVIDENCE".equals(domain)){
                UUID student=jdbc.sql("select student_id from student_consent where id=:id").param("id",c.id()).query(UUID.class).single();
                jdbc.sql("select id from student where id=:id for update").param("id",student).query(UUID.class).single();
            }
            jdbc.sql("select id from "+d.table()+" where id=:id for update").param("id",c.id()).query(UUID.class).single();
            Candidate current=candidates(domain,now,now,now).stream().filter(x->x.id().equals(c.id())).findFirst().orElseThrow();
            if(current.held()||current.referenced()||!current.version().equals(c.version())||!Objects.equals(current.storageKey(),c.storageKey()))throw new RetentionException("RETENTION_TARGET_CHANGED");
            switch(domain){
                case "INQUIRY"->jdbc.sql("delete from inquiry where id=:id").param("id",c.id()).update();
                case "STUDENT_PRIVATE"->{
                    jdbc.sql("update guardian_contact set name=null,phone_ciphertext=null,phone_hash=null,phone_last4=null,email_ciphertext=null,email_hash=null,email_domain=null,preferred_channel='MANUAL',relationship='GUARDIAN',relationship_detail=null where student_id=:id").param("id",c.id()).update();
                    jdbc.sql("update student set student_name=null,student_name_search=null,school_name=null,birthday=null,private_purged_at=:now where id=:id").param("now",ts(now)).param("id",c.id()).update();
                }
                case "CONSENT_EVIDENCE"->{
                    if(c.storageKey()==null||!c.storageKey().startsWith("private-evidence/"))throw new RetentionException("RETENTION_STORAGE_INVALID");
                    UUID asset=jdbc.sql("select evidence_asset_id from student_consent where id=:id").param("id",c.id()).query(UUID.class).single();
                    jdbc.sql("select id from media_asset where id=:id for update").param("id",asset).query(UUID.class).single();
                    evidenceStorage.delete(c.storageKey());
                    jdbc.sql("update student_consent set evidence_asset_id=null,evidence_purged_at=:now where id=:id").param("now",ts(now)).param("id",c.id()).update();
                    jdbc.sql("delete from media_asset where id=:id").param("id",asset).update();
                }
                case "TRANSFER_FILE"->{
                    transferStorage.delete(c.storageKey());
                    jdbc.sql("update data_transfer_job set status='EXPIRED',version=version+1,sha256=repeat('0',64),source_file_name=case when direction='IMPORT' then 'EXPIRED.csv' else null end where id=:id").param("id",c.id()).update();
                    jdbc.sql("update data_transfer_row set payload_ciphertext=null,dedup_hash=repeat('0',64),masked_summary='EXPIRED' where job_id=:id").param("id",c.id()).update();
                    jdbc.sql("update data_transfer_work set state='EXPIRED',payload_ciphertext=null where job_id=:id").param("id",c.id()).update();
                }
                case "NOTIFICATION_PAYLOAD"->{
                    UUID batch=jdbc.sql("select batch_key from notification_message where id=:id").param("id",c.id()).query(UUID.class).single();
                    jdbc.sql("update notification_message set recipient_ciphertext=null,recipient_hash=null,recipient_last4=null,subject_ciphertext=null,body_ciphertext=null,variables_ciphertext=null,payload_purged_at=:now where id=:id").param("now",ts(now)).param("id",c.id()).update();
                    jdbc.sql("update notification_batch b set recipient_filter_ciphertext=null,subject_template_ciphertext=null,body_template_ciphertext=null,variables_ciphertext=null,payload_purged_at=:now where b.id=:id and b.payload_purged_at is null and not exists(select 1 from notification_message m where m.batch_key=b.id and m.payload_purged_at is null)").param("now",ts(now)).param("id",batch).update();
                }
                default->throw new RetentionException("VALIDATION_ERROR");
            }
            connection.releaseSavepoint(savepoint);
        }catch(Exception e){try{if(savepoint!=null)connection.rollback(savepoint);}catch(SQLException rollback){e.addSuppressed(rollback);}throw new RetentionException("RETENTION_ITEM_FAILED");}
        finally{DataSourceUtils.releaseConnection(connection,dataSource);}
    }
    @Override public void finish(UUID id,int processed,int failed,Map<String,Integer> errors,String status,String fingerprint,Instant now){
        String json;try{json=mapper.writeValueAsString(errors);}catch(Exception e){throw new IllegalStateException(e);}
        jdbc.sql("update retention_run set processed_count=:processed,failed_count=:failed,failure_summary=cast(:errors as jsonb),status=:status,cursor=:fingerprint,completed_at=:completed where id=:id").param("processed",processed).param("failed",failed).param("errors",json).param("status",status).param("fingerprint",fingerprint).param("completed","PROCESSING".equals(status)?null:ts(now),Types.TIMESTAMP).param("id",id).update();
    }
    private static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}
    private static Instant instant(ResultSet r,String column)throws SQLException{Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant();}
}
