package com.ramiart.admin.consent.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.consent.application.ConsentService.ConsentException;
import com.ramiart.admin.media.application.*;
import com.ramiart.admin.media.application.MediaModels.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ConsentEvidenceService {
    private final JdbcClient jdbc;
    private final MediaRepository repository;
    private final ImageValidator validator;
    private final ConsentEvidenceStorage storage;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ConsentEvidenceService.class);
    public ConsentEvidenceService(JdbcClient jdbc,MediaRepository repository,ImageValidator validator,ConsentEvidenceStorage storage,AuditRecorder audit,Clock clock,PlatformTransactionManager manager){this.jdbc=jdbc;this.repository=repository;this.validator=validator;this.storage=storage;this.audit=audit;this.clock=clock;this.tx=new TransactionTemplate(manager);}
    public EvidenceAsset upload(UUID studentId,byte[] bytes,String fileName,String mimeType,UUID key,Authentication auth,RequestMetadata meta){
        UUID actor=require(auth);if(studentId==null||key==null)throw new ConsentException("VALIDATION_ERROR");
        ValidatedImage image=validator.validate(bytes,fileName,mimeType);
        UUID id=UUID.randomUUID();String suffix=switch(image.mimeType()){case "image/png"->".png";case "image/jpeg"->".jpg";default->".webp";};
        String storageKey="private-evidence/"+id+suffix;
        String scope=actor+":CONSENT_EVIDENCE_UPLOAD:"+studentId;
        String hash=image.sha256();boolean[] written={false};
        try{
            EvidenceAsset result=tx.execute(status->{
                jdbc.sql("select id from student where id=:id and private_purged_at is null for update").param("id",studentId).query(UUID.class).optional().orElseThrow(()->new ConsentException("STUDENT_NOT_FOUND"));
                MediaRepository.IdempotencyClaim claim=repository.claim(scope,key,hash);
                if(!claim.claimed())return read(claim.resourceId(),studentId);
                storage.upload(storageKey,image.bytes(),image.mimeType());written[0]=true;
                Instant expiry=clock.instant().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MICROS);
                repository.insert(new StoredAsset(id,storageKey,"/private-evidence/"+id+suffix,"consent-evidence"+suffix,hash,image.mimeType(),image.bytes().length,image.width(),image.height(),expiry),actor);
                jdbc.sql("insert into consent_evidence_upload(asset_id,student_id,created_by) values(:asset,:student,:actor)").param("asset",id).param("student",studentId).param("actor",actor).update();
                repository.complete(scope,key,id,201);
                audit.record(new Event(clock.instant(),meta.requestId(),"MGT-CONSENT-MANAGE","OPERATION","ADMIN",actor,null,"CONSENT_EVIDENCE_UPLOADED","MEDIA_ASSET",id,"SUCCESS",null,meta.ipAddress(),meta.userAgent(),Map.of("mimeType",image.mimeType(),"fileSize",image.bytes().length)));
                return read(id,studentId);
            });
            if(result==null)throw new ConsentException("CONSENT_EVIDENCE_STORAGE_UNAVAILABLE");return result;
        }catch(RuntimeException e){
            if(written[0])try{storage.delete(storageKey);}catch(RuntimeException cleanup){LOG.error("Private evidence upload compensation failed: assetId={}",id);}
            if(e instanceof MediaException||e instanceof ConsentException)throw e;
            throw new ConsentException("CONSENT_EVIDENCE_STORAGE_UNAVAILABLE");
        }
    }
    private EvidenceAsset read(UUID id,UUID student){return jdbc.sql("select a.id,a.mime_type,a.file_size,a.width,a.height,a.status,a.expires_at from media_asset a join consent_evidence_upload e on e.asset_id=a.id where a.id=:id and e.student_id=:student and a.status='READY'").param("id",id).param("student",student).query((r,n)->new EvidenceAsset(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getInt(4),r.getInt(5),r.getString(6),r.getTimestamp(7).toInstant())).optional().orElseThrow(()->new ConsentException("CONSENT_EVIDENCE_NOT_FOUND"));}
    public int cleanup(){
        List<UUID> ids=jdbc.sql("select a.id from media_asset a where a.storage_key like 'private-evidence/%' and a.expires_at<=:now order by a.expires_at,a.id limit 100").param("now",java.sql.Timestamp.from(clock.instant())).query(UUID.class).list();int count=0;
        for(UUID id:ids)try{Boolean deleted=tx.execute(status->{
            jdbc.sql("select pg_advisory_xact_lock(hashtext('retention-governance'))").query((r,n)->true).single();
            var student=jdbc.sql("select student_id from consent_evidence_upload where asset_id=:id").param("id",id).query(UUID.class).optional();
            if(student.isEmpty())return false;
            jdbc.sql("select id from student where id=:id for update").param("id",student.get()).query(UUID.class).single();
            var candidate=jdbc.sql("select a.storage_key from media_asset a join consent_evidence_upload e on e.asset_id=a.id where a.id=:id and a.expires_at<=:now and not exists(select 1 from student_consent c where c.evidence_asset_id=a.id) and not exists(select 1 from media_asset_reference r where r.asset_id=a.id) and not exists(select 1 from retention_hold h where h.target_type='STUDENT' and h.target_id=e.student_id and h.status='ACTIVE' and h.starts_at<=:now and (h.ends_at is null or h.ends_at>:now)) for update of a")
                    .param("id",id).param("now",java.sql.Timestamp.from(clock.instant())).query(String.class).optional();
            if(candidate.isEmpty())return false;storage.delete(candidate.get());return jdbc.sql("delete from media_asset where id=:id").param("id",id).update()==1;
        });if(Boolean.TRUE.equals(deleted))count++;}catch(RuntimeException e){LOG.warn("Private evidence cleanup failed: assetId={}",id);}
        return count;
    }
    private static UUID require(Authentication a){if(a==null||a.getAuthorities().stream().noneMatch(x->"CONSENT_WRITE".equals(x.getAuthority())))throw new ConsentException("CONSENT_WRITE_DENIED");try{return UUID.fromString(a.getName());}catch(RuntimeException e){throw new ConsentException("CONSENT_WRITE_DENIED");}}
    public record EvidenceAsset(UUID id,String mimeType,long fileSize,int width,int height,String status,Instant expiresAt){}
}
