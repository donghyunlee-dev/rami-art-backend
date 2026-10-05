package com.ramiart.admin.consent.infrastructure;

import static com.ramiart.admin.consent.application.ConsentModels.*;

import com.ramiart.admin.consent.application.ConsentPolicyValidator;
import com.ramiart.admin.consent.application.ConsentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConsentRepository implements ConsentRepository {
    private static final ZoneId STUDIO_ZONE=ZoneId.of("Asia/Seoul");
    private static final String POLICY_COLUMNS="id,type,revision,status,title,body,required,valid_days,evidence_required,version,created_by,created_at,published_by,published_at";
    private final NamedParameterJdbcTemplate jdbc;
    private volatile Boolean notificationTableAvailable;
    private volatile Boolean galleryTableAvailable;
    public JdbcConsentRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override public List<Policy> policies(String type){
        if(type==null)return jdbc.query("select "+POLICY_COLUMNS+" from consent_policy order by type,revision desc",this::policyRow);
        return jdbc.query("select "+POLICY_COLUMNS+" from consent_policy where type=:type order by revision desc",Map.of("type",type),this::policyRow);
    }
    @Override public Optional<Policy> policy(UUID id){return jdbc.query("select "+POLICY_COLUMNS+" from consent_policy where id=:id",Map.of("id",id),this::policyRow).stream().findFirst();}
    @Override public Optional<Policy> createDraft(String type,PolicyDraft draft,UUID actor){
        UUID id=UUID.randomUUID();MapSqlParameterSource p=new MapSqlParameterSource().addValue("id",id).addValue("type",type).addValue("title",draft.title().trim()).addValue("body",draft.body().trim()).addValue("required",draft.required()).addValue("days",draft.validDays()).addValue("evidence",draft.evidenceRequired()).addValue("actor",actor);
        return jdbc.query("insert into consent_policy(id,type,revision,status,title,body,required,valid_days,evidence_required,created_by) values(:id,:type,(select coalesce(max(revision),0)+1 from consent_policy where type=:type),'DRAFT',:title,:body,:required,:days,:evidence,:actor) returning "+POLICY_COLUMNS,p,this::policyRow).stream().findFirst();
    }
    @Override public int updateDraft(UUID id,long version,PolicyDraft draft){
        return jdbc.update("update consent_policy set title=:title,body=:body,required=:required,valid_days=:days,evidence_required=:evidence,version=version+1 where id=:id and version=:version and status='DRAFT'",
                new MapSqlParameterSource().addValue("id",id).addValue("version",version).addValue("title",draft.title().trim()).addValue("body",draft.body().trim()).addValue("required",draft.required()).addValue("days",draft.validDays()).addValue("evidence",draft.evidenceRequired()));
    }
    @Override public Optional<Policy> publish(UUID id,long version,UUID actor){
        String type=jdbc.query("select type from consent_policy where id=:id and version=:version and status='DRAFT' for update",Map.of("id",id,"version",version),(r,n)->r.getString(1)).stream().findFirst().orElse(null);
        if(type==null)return Optional.empty();
        jdbc.update("update consent_policy set status='ARCHIVED',version=version+1 where type=:type and status='PUBLISHED'",Map.of("type",type));
        return jdbc.query("update consent_policy set status='PUBLISHED',published_by=:actor,published_at=statement_timestamp(),version=version+1 where id=:id and version=:version and status='DRAFT' returning "+POLICY_COLUMNS,
                Map.of("id",id,"version",version,"actor",actor),this::policyRow).stream().findFirst();
    }
    @Override public boolean studentExists(UUID studentId){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from student where id=:id)",Map.of("id",studentId),Boolean.class));}
    @Override public boolean guardianBelongsTo(UUID guardianId,UUID studentId){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from guardian_contact where id=:guardian and student_id=:student)",Map.of("guardian",guardianId,"student",studentId),Boolean.class));}
    @Override public List<ConsentTypeStatus> studentConsents(UUID studentId){
        boolean galleryAvailable=galleryTableAvailable();
        String galleryJoin=galleryAvailable?"left join lateral(select count(*) artwork_count from gallery_artwork a where a.student_consent_id=c.id and a.status='PUBLISHED' and a.visible)g on true":"";
        String notificationJoin=notificationTableAvailable()?"left join lateral(select count(*) notification_count from notification_message m where m.consent_id=c.id and m.optional_notice and m.status='QUEUED')n on true":"";
        String artworkCount=galleryAvailable?"coalesce(g.artwork_count,0)":"0";
        String notificationCount=notificationTableAvailable()?"coalesce(n.notification_count,0)":"0";
        String sql="""
                select p.id p_id,p.type,p.revision p_revision,p.status p_status,p.title,p.body,p.required,p.valid_days,p.evidence_required,p.version p_version,p.created_by p_created_by,p.created_at p_created_at,p.published_by p_published_by,p.published_at p_published_at,
                  c.id c_id,c.consent_policy_id c_policy_id,c.policy_type,c.method,c.guardian_contact_id,c.consented_at,c.expires_on,c.evidence_asset_id,c.version c_version,c.status c_status,c.c_revision,
                  ARTWORK_COUNT artwork_count,NOTIFICATION_COUNT notification_count
                from consent_policy p
                left join lateral(select sc.*,cp.revision as c_revision from student_consent sc join consent_policy cp on cp.id=sc.consent_policy_id where sc.student_id=:student and sc.policy_type=p.type and sc.status='ACTIVE' order by sc.consented_at desc,sc.id desc limit 1)c on true
                GALLERY_JOIN
                NOTIFICATION_JOIN
                where p.status='PUBLISHED' order by p.type
                """.replace("ARTWORK_COUNT",artworkCount).replace("GALLERY_JOIN",galleryJoin).replace("NOTIFICATION_COUNT",notificationCount).replace("NOTIFICATION_JOIN",notificationJoin);
        try{return jdbc.query(sql,Map.of("student",studentId),(r,n)->{
                    Policy policy=new Policy(r.getObject("p_id",UUID.class),r.getString("type"),r.getInt("p_revision"),r.getString("p_status"),r.getString("title"),r.getString("body"),r.getBoolean("required"),r.getObject("valid_days",Integer.class),r.getBoolean("evidence_required"),r.getLong("p_version"),r.getObject("p_created_by",UUID.class),r.getObject("p_created_at",OffsetDateTime.class),r.getObject("p_published_by",UUID.class),r.getObject("p_published_at",OffsetDateTime.class));
                    StudentConsent consent=r.getObject("c_id")==null?null:new StudentConsent(r.getObject("c_id",UUID.class),r.getString("policy_type"),r.getObject("c_policy_id",UUID.class),r.getInt("c_revision"),r.getString("c_status"),r.getString("method"),r.getObject("guardian_contact_id",UUID.class),r.getObject("consented_at",OffsetDateTime.class),r.getObject("expires_on",LocalDate.class),r.getObject("evidence_asset_id",UUID.class),r.getLong("c_version"));
                    boolean current=consent!=null&&consent.policyId().equals(policy.id())&&(consent.expiresOn()==null||!consent.expiresOn().isBefore(LocalDate.now(STUDIO_ZONE)));
                    return new ConsentTypeStatus(policy.type(),policy,consent,current?"ACTIVE":consent==null?"MISSING":"RECONSENT_REQUIRED",consent==null?null:consent.expiresOn(),new Uses(r.getInt("artwork_count"),r.getInt("notification_count")),current?List.of("REVOKE"):List.of("COLLECT"));
                });}catch(org.springframework.dao.DataAccessException exception){throw exception;}
    }
    private boolean notificationTableAvailable(){
        Boolean available=notificationTableAvailable;
        if(available==null){available=Boolean.TRUE.equals(jdbc.getJdbcTemplate().queryForObject("select to_regclass('public.notification_message') is not null",Boolean.class));notificationTableAvailable=available;}
        return available;
    }
    private boolean galleryTableAvailable(){
        Boolean available=galleryTableAvailable;
        if(available==null){available=Boolean.TRUE.equals(jdbc.getJdbcTemplate().queryForObject("select to_regclass('public.gallery_artwork') is not null",Boolean.class));galleryTableAvailable=available;}
        return available;
    }
    @Override public Optional<Policy> currentPublishedPolicy(UUID id,String type){return jdbc.query("select "+POLICY_COLUMNS+" from consent_policy where id=:id and type=:type and status='PUBLISHED'",Map.of("id",id,"type",type),this::policyRow).stream().findFirst();}
    @Override public boolean privateReadyEvidence(UUID assetId){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from media_asset where id=:id and status='READY' and storage_key like 'private-evidence/%')",Map.of("id",assetId),Boolean.class));}
    @Override public UUID insertConsent(UUID studentId,Policy policy,CollectConsent request,UUID actor,OffsetDateTime consentedAt){
        jdbc.update("update student_consent set status='EXPIRED',version=version+1 where student_id=:student and policy_type=:type and status='ACTIVE'",Map.of("student",studentId,"type",policy.type()));
        UUID id=UUID.randomUUID();LocalDate expires=policy.validDays()==null?null:consentedAt.atZoneSameInstant(STUDIO_ZONE).toLocalDate().plusDays(policy.validDays());
        jdbc.update("insert into student_consent(id,student_id,consent_policy_id,policy_type,guardian_contact_id,method,status,consented_at,expires_on,evidence_asset_id,created_by) values(:id,:student,:policy,:type,:guardian,:method,'ACTIVE',:at,:expires,:evidence,:actor)",new MapSqlParameterSource().addValue("id",id).addValue("student",studentId).addValue("policy",policy.id()).addValue("type",policy.type()).addValue("guardian",request.guardianContactId()).addValue("method",request.method()).addValue("at",consentedAt).addValue("expires",expires).addValue("evidence",request.evidenceAssetId()).addValue("actor",actor));
        return id;
    }
    @Override public Optional<StudentConsent> consent(UUID id){return jdbc.query("select c.*,p.revision from student_consent c join consent_policy p on p.id=c.consent_policy_id where c.id=:id",Map.of("id",id),(r,n)->new StudentConsent(r.getObject("id",UUID.class),r.getString("policy_type"),r.getObject("consent_policy_id",UUID.class),r.getInt("revision"),r.getString("status"),r.getString("method"),r.getObject("guardian_contact_id",UUID.class),r.getObject("consented_at",OffsetDateTime.class),r.getObject("expires_on",LocalDate.class),r.getObject("evidence_asset_id",UUID.class),r.getLong("version"))).stream().findFirst();}
    @Override public boolean revoke(UUID id,long version,String reason,UUID actor){
        int changed=jdbc.update("update student_consent set status='REVOKED',revoked_at=statement_timestamp(),revoked_by=:actor,revoke_reason=:reason,version=version+1 where id=:id and version=:version and status='ACTIVE'",Map.of("id",id,"version",version,"reason",reason,"actor",actor));
        if(changed!=1)return false;
        jdbc.update("update gallery_artwork set visible=false,featured=false,featured_order=null,version=version+1 where student_consent_id=:id and status='PUBLISHED' and visible",Map.of("id",id));
        jdbc.update("update notification_message set status='CANCELLED',cancelled_at=statement_timestamp(),cancelled_by=:actor,cancel_reason='동의 철회로 자동 취소',version=version+1 where consent_id=:id and optional_notice and status='QUEUED'",Map.of("id",id,"actor",actor));
        return true;
    }
    @Override public int expireConsents(int limit){
        return jdbc.update("with expired as (select id from student_consent where status='ACTIVE' and expires_on<(statement_timestamp() at time zone 'Asia/Seoul')::date order by expires_on,id for update skip locked limit :limit) update student_consent c set status='EXPIRED',version=version+1 from expired where c.id=expired.id",Map.of("limit",limit));
    }
    @Override public Optional<String> privateEvidenceStorageKey(UUID consentId){return jdbc.query("select a.storage_key from student_consent c join media_asset a on a.id=c.evidence_asset_id where c.id=:id and a.storage_key like 'private-evidence/%'",Map.of("id",consentId),(r,n)->r.getString(1)).stream().findFirst();}
    @Override public IdempotencyClaim claim(String scope,UUID key,String hash){
        int inserted=jdbc.update("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing",Map.of("scope",scope,"key",key,"hash",hash));
        if(inserted==1)return new IdempotencyClaim(true,null);
        Map<String,Object> value=jdbc.queryForMap("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key",Map.of("scope",scope,"key",key));
        if(!hash.equals(value.get("request_hash")))throw new ConsentConflictException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(value.get("state"))||value.get("resource_id")==null)throw new ConsentConflictException("IDEMPOTENCY_IN_PROGRESS");
        return new IdempotencyClaim(false,(UUID)value.get("resource_id"));
    }
    @Override public void complete(String scope,UUID key,UUID resourceId,int status){jdbc.update("update idempotency_record set state='COMPLETED',resource_id=:resource,response_status=:status where scope=:scope and idempotency_key=:key",Map.of("resource",resourceId,"status",status,"scope",scope,"key",key));}

    public static final class ConsentConflictException extends RuntimeException {private final String code;public ConsentConflictException(String code){super(code);this.code=code;}public String code(){return code;}}

    private Policy policyRow(ResultSet r,int n)throws SQLException{return new Policy(r.getObject("id",UUID.class),r.getString("type"),r.getInt("revision"),r.getString("status"),r.getString("title"),r.getString("body"),r.getBoolean("required"),r.getObject("valid_days",Integer.class),r.getBoolean("evidence_required"),r.getLong("version"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("published_by",UUID.class),r.getObject("published_at",OffsetDateTime.class));}
}
