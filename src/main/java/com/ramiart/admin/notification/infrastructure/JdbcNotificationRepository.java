package com.ramiart.admin.notification.infrastructure;

import static com.ramiart.admin.notification.application.NotificationModels.*;
import static com.ramiart.admin.notification.application.NotificationRepository.*;

import com.ramiart.admin.notification.application.NotificationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<MessageSummary> SUMMARY=(r,n)->new MessageSummary(r.getObject("id",UUID.class),
            r.getObject("batch_key",UUID.class),r.getString("type"),r.getString("channel"),r.getString("status"),
            r.getObject("scheduled_at",OffsetDateTime.class),r.getObject("created_at",OffsetDateTime.class),r.getLong("version"));
    public JdbcNotificationRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override public List<Candidate> candidates(RecipientFilter filter,OffsetDateTime at){
        RecipientFilter safe=filter==null?new RecipientFilter(List.of(),List.of()):filter;
        StringBuilder sql=new StringBuilder("""
                select s.id student_id,s.student_name,g.id guardian_id,g.name guardian_name,g.phone_ciphertext,g.phone_hash,g.phone_last4,
                       g.email_ciphertext,g.email_hash,g.email_domain,
                       (select c.id from student_consent c join consent_policy p on p.id=c.consent_policy_id
                         where c.student_id=s.id and c.guardian_contact_id=g.id and c.policy_type='OPTIONAL_NOTIFICATION'
                           and c.status='ACTIVE' and p.status='PUBLISHED'
                           and (c.expires_on is null or c.expires_on >= (:at at time zone 'Asia/Seoul')::date)
                         order by c.consented_at desc,c.id desc limit 1) consent_id
                  from student s left join guardian_contact g on g.student_id=s.id and g.primary_contact
                 where s.status='ACTIVE'
                """);
        MapSqlParameterSource params=new MapSqlParameterSource("at",at);
        if(safe.studentIds()!=null&&!safe.studentIds().isEmpty()){sql.append(" and s.id in (:studentIds)");params.addValue("studentIds",safe.studentIds());}
        if(safe.classGroupIds()!=null&&!safe.classGroupIds().isEmpty()){
            sql.append(" and exists(select 1 from student_schedule_assignment a join schedule_slot ss on ss.id=a.schedule_slot_id where a.student_id=s.id and ss.class_group_id in (:classGroupIds) and ss.status='ACTIVE' and a.effective_from <= (:at at time zone 'Asia/Seoul')::date and (a.effective_to is null or a.effective_to >= (:at at time zone 'Asia/Seoul')::date))");
            params.addValue("classGroupIds",safe.classGroupIds());
        }
        sql.append(" order by s.id limit 501");
        return jdbc.query(sql.toString(),params,(r,n)->new Candidate(r.getObject("student_id",UUID.class),r.getString("student_name"),
                r.getObject("guardian_id",UUID.class),r.getString("guardian_name"),r.getBytes("phone_ciphertext"),r.getString("phone_hash"),r.getString("phone_last4"),
                r.getBytes("email_ciphertext"),r.getString("email_hash"),r.getString("email_domain"),r.getObject("consent_id",UUID.class),r.getObject("guardian_id")!=null));
    }
    @Override public Claim claim(String scope,UUID key,String hash){
        int inserted=jdbc.update("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing",Map.of("scope",scope,"key",key,"hash",hash));
        if(inserted==1)return new Claim(true,null);
        Map<String,Object> row=jdbc.queryForMap("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key",Map.of("scope",scope,"key",key));
        if(!hash.equals(row.get("request_hash")))throw new NotificationRepository.NotificationConflictException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(row.get("state"))||row.get("resource_id")==null)throw new NotificationRepository.NotificationConflictException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false,(UUID)row.get("resource_id"));
    }
    @Override public void complete(String scope,UUID key,UUID resourceId,int status){jdbc.update("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key",Map.of("id",resourceId,"status",status,"scope",scope,"key",key));}
    @Override public int batchQueuedCount(UUID batchId){return jdbc.queryForObject("select eligible_count from notification_batch where id=:batch",Map.of("batch",batchId),Integer.class);}
    @Override public void createBatch(UUID id,DraftRequest request,int eligible,int missing,int excluded,int deduplicated,String fingerprint,UUID actor,byte[] filter,byte[] subjectTemplate,byte[] bodyTemplate,byte[] variables){
        jdbc.update("insert into notification_batch(id,type,channel,recipient_filter_ciphertext,subject_template_ciphertext,body_template_ciphertext,variables_ciphertext,preview_fingerprint,eligible_count,missing_contact_count,consent_excluded_count,deduplicated_count,scheduled_at,created_by) values(:id,:type,:channel,:filter,:subject,:body,:variables,:fingerprint,:eligible,:missing,:excluded,:deduplicated,:scheduled,:actor)",new MapSqlParameterSource().addValue("id",id).addValue("type",request.type()).addValue("channel",request.channel()).addValue("filter",filter).addValue("subject",subjectTemplate).addValue("body",bodyTemplate).addValue("variables",variables).addValue("fingerprint",fingerprint).addValue("eligible",eligible).addValue("missing",missing).addValue("excluded",excluded).addValue("deduplicated",deduplicated).addValue("scheduled",request.scheduledAt()).addValue("actor",actor));
    }
    @Override public void createMessage(UUID id,UUID batchId,Candidate target,DraftRequest request,UUID actor,byte[] recipient,String recipientHash,String recipientLast4,byte[] subject,byte[] body,byte[] variables,String scope){
        jdbc.update("insert into notification_message(id,batch_key,type,channel,student_id,guardian_contact_id,recipient_ciphertext,recipient_hash,recipient_last4,subject_ciphertext,body_ciphertext,consent_id,status,scheduled_at,optional_notice,variables_ciphertext,idempotency_scope,created_by) values(:id,:batch,:type,:channel,:student,:guardian,:recipient,:recipientHash,:recipientLast4,:subject,:body,:consent,'QUEUED',:scheduled,:optional,:variables,:scope,:actor)",new MapSqlParameterSource().addValue("id",id).addValue("batch",batchId).addValue("type",request.type()).addValue("channel",request.channel()).addValue("student",target.studentId()).addValue("guardian",target.guardianId()).addValue("recipient",recipient).addValue("recipientHash",recipientHash).addValue("recipientLast4",recipientLast4).addValue("subject",subject).addValue("body",body).addValue("consent",target.consentId()).addValue("scheduled",request.scheduledAt()).addValue("optional",request.optionalNotice()).addValue("variables",variables).addValue("scope",scope).addValue("actor",actor));
    }
    @Override public List<MessageSummary> page(int page,int size,String status,String type,String channel){
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("offset",page*size).addValue("limit",size);StringBuilder sql=new StringBuilder("select id,batch_key,type,channel,status,scheduled_at,created_at,version from notification_message where true");
        if(status!=null){sql.append(" and status=:status");p.addValue("status",status);}if(type!=null){sql.append(" and type=:type");p.addValue("type",type);}if(channel!=null){sql.append(" and channel=:channel");p.addValue("channel",channel);}
        sql.append(" order by created_at desc,id desc limit :limit offset :offset");return jdbc.query(sql.toString(),p,SUMMARY);
    }
    @Override public long count(String status,String type,String channel){MapSqlParameterSource p=new MapSqlParameterSource();StringBuilder sql=new StringBuilder("select count(*) from notification_message where true");if(status!=null){sql.append(" and status=:status");p.addValue("status",status);}if(type!=null){sql.append(" and type=:type");p.addValue("type",type);}if(channel!=null){sql.append(" and channel=:channel");p.addValue("channel",channel);}return jdbc.queryForObject(sql.toString(),p,Long.class);}
    @Override public Optional<StoredMessageDetail> detail(UUID id){
        List<StoredMessageDetail> rows=jdbc.query("select id,batch_key,type,channel,status,scheduled_at,created_at,version,subject_ciphertext,body_ciphertext,recipient_last4,attempt_count,last_error_code from notification_message where id=:id",Map.of("id",id),(r,n)->new StoredMessageDetail(new MessageSummary(r.getObject("id",UUID.class),r.getObject("batch_key",UUID.class),r.getString("type"),r.getString("channel"),r.getString("status"),r.getObject("scheduled_at",OffsetDateTime.class),r.getObject("created_at",OffsetDateTime.class),r.getLong("version")),r.getBytes("subject_ciphertext"),r.getBytes("body_ciphertext"),r.getString("recipient_last4"),r.getInt("attempt_count"),r.getString("last_error_code"),List.of()));
        if(rows.isEmpty())return Optional.empty();StoredMessageDetail row=rows.getFirst();List<Attempt> attempts=jdbc.query("select attempt_number,result,provider,error_code,started_at,completed_at from notification_attempt where notification_message_id=:id order by attempt_number",Map.of("id",id),(r,n)->new Attempt(r.getInt("attempt_number"),r.getString("result"),r.getString("provider"),r.getString("error_code"),r.getObject("started_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class)));
        return Optional.of(new StoredMessageDetail(row.message(),row.subject(),row.body(),row.recipientLast4(),row.attemptCount(),row.lastErrorCode(),attempts));
    }
    @Override public boolean cancel(UUID id,long version,String reason,UUID actor){return jdbc.update("update notification_message set status='CANCELLED',cancelled_at=statement_timestamp(),cancelled_by=:actor,cancel_reason=:reason,version=version+1 where id=:id and version=:version and status='QUEUED'",Map.of("id",id,"version",version,"reason",reason,"actor",actor))==1;}
    @Override public boolean retry(UUID id){return jdbc.update("update notification_message set status='QUEUED',last_error_code=null,next_attempt_at=null,version=version+1 where id=:id and status='FAILED' and attempt_count<5 and coalesce(last_error_code,'') not like 'PERMANENT_%'",Map.of("id",id))==1;}
    @Override @Transactional public Optional<Dispatch> claimNext(String channel,OffsetDateTime now){
        jdbc.update("update notification_message m set status='CANCELLED',cancelled_at=:now,cancel_reason='선택 동의가 유효하지 않아 발송을 취소했습니다.',version=version+1 where m.status='QUEUED' and m.optional_notice and (m.consent_id is null or not exists(select 1 from student_consent c join consent_policy p on p.id=c.consent_policy_id where c.id=m.consent_id and c.status='ACTIVE' and p.status='PUBLISHED' and c.guardian_contact_id=m.guardian_contact_id and (c.expires_on is null or c.expires_on >= (:now at time zone 'Asia/Seoul')::date)))",Map.of("now",now));
        List<Dispatch> candidates=jdbc.query("select id,attempt_count,channel,recipient_ciphertext,subject_ciphertext,body_ciphertext,consent_id,optional_notice,last_attempt_at from notification_message where channel=:channel and ((status='QUEUED' and scheduled_at<=:now) or (status='FAILED' and next_attempt_at<=:now and attempt_count<5 and coalesce(last_error_code,'') not like 'PERMANENT_%')) and (not optional_notice or exists(select 1 from student_consent c join consent_policy p on p.id=c.consent_policy_id where c.id=consent_id and c.status='ACTIVE' and p.status='PUBLISHED' and c.guardian_contact_id=notification_message.guardian_contact_id and (c.expires_on is null or c.expires_on >= (:now at time zone 'Asia/Seoul')::date))) order by scheduled_at,id for update skip locked limit 1",new MapSqlParameterSource().addValue("channel",channel).addValue("now",now),(r,n)->new Dispatch(r.getObject("id",UUID.class),r.getInt("attempt_count")+1,r.getString("channel"),r.getBytes("recipient_ciphertext"),r.getBytes("subject_ciphertext"),r.getBytes("body_ciphertext"),r.getObject("consent_id",UUID.class),r.getBoolean("optional_notice"),now));
        if(candidates.isEmpty())return Optional.empty();Dispatch found=candidates.getFirst();jdbc.update("update notification_message set status='SENDING',attempt_count=:attempt,last_attempt_at=:now,next_attempt_at=null,version=version+1 where id=:id",Map.of("attempt",found.attemptNumber(),"now",now,"id",found.id()));return Optional.of(found);
    }
    @Override @Transactional public void finish(Dispatch dispatch,String provider,String providerMessageId,String errorCode,boolean retryable,OffsetDateTime completedAt,OffsetDateTime nextAttemptAt){
        boolean success=providerMessageId!=null;boolean willRetry=!success&&retryable&&dispatch.attemptNumber()<5;String result=success?"SUCCESS":retryable?"TRANSIENT_FAILURE":"PERMANENT_FAILURE";String storedError=success?null:willRetry?errorCode:"PERMANENT_"+errorCode;
        jdbc.update("insert into notification_attempt(id,notification_message_id,attempt_number,result,provider,provider_message_id,error_code,started_at,completed_at) values(:id,:message,:attempt,:result,:provider,:providerId,:error,:started,:completed)",new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("message",dispatch.id()).addValue("attempt",dispatch.attemptNumber()).addValue("result",result).addValue("provider",provider).addValue("providerId",success?providerMessageId:null).addValue("error",success?null:errorCode).addValue("started",dispatch.startedAt()).addValue("completed",completedAt));
        jdbc.update("update notification_message set status=:status,sent_at=:sent,provider=:provider,provider_message_id=:providerId,last_error_code=:error,next_attempt_at=:next,version=version+1 where id=:id and status='SENDING'",new MapSqlParameterSource().addValue("status",success?"SENT":"FAILED").addValue("sent",success?completedAt:null).addValue("provider",success?provider:null).addValue("providerId",success?providerMessageId:null).addValue("error",storedError).addValue("next",willRetry?nextAttemptAt:null).addValue("id",dispatch.id()));
    }
}
