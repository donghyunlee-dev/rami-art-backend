package com.ramiart.admin.consent.application;

import static com.ramiart.admin.consent.application.ConsentModels.*;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.consent.application.ConsentRepository.IdempotencyClaim;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentService {
    private static final Set<String> TYPES=Set.of("PERSONAL_DATA_REQUIRED","MEDIA_PUBLICATION","PORTRAIT","OPTIONAL_NOTIFICATION");
    private static final Set<String> METHODS=Set.of("PAPER","DIGITAL","MIGRATED");
    private final ConsentRepository repository;
    private final ConsentEvidenceStorage evidenceStorage;
    private final AuditRecorder audit;
    private final Clock clock;

    public ConsentService(ConsentRepository repository,ConsentEvidenceStorage evidenceStorage,AuditRecorder audit,Clock clock){this.repository=repository;this.evidenceStorage=evidenceStorage;this.audit=audit;this.clock=clock;}

    @Transactional(readOnly=true)
    public List<Policy> policies(String type,org.springframework.security.core.Authentication auth){require(auth,"CONSENT_READ");if(type!=null&&!TYPES.contains(type))fail("VALIDATION_ERROR");return repository.policies(type);}

    @Transactional
    public Policy createDraft(String type,PolicyDraft draft,UUID key,org.springframework.security.core.Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"CONSENT_WRITE");validate(type,draft);String scope=actor+":CONSENT_POLICY_DRAFT:"+type;String requestHash=hash(type+"|"+draft);
        IdempotencyClaim claim=repository.claim(scope,key,requestHash);if(!claim.claimed())return repository.policy(claim.resourceId()).orElseThrow(()->new ConsentException("CONSENT_POLICY_NOT_FOUND"));
        Policy result;try{result=repository.createDraft(type,draft,actor).orElseThrow(()->new ConsentException("CONSENT_DRAFT_CREATE_FAILED"));}catch(DataIntegrityViolationException exception){throw new ConsentException("CONSENT_DRAFT_EXISTS");}
        repository.complete(scope,key,result.id(),201);event(actor,metadata,"CONSENT_POLICY_DRAFT_CREATED","CONSENT_POLICY",result.id(),Map.of("type",type,"revision",result.revision()));return result;
    }

    @Transactional
    public Policy updateDraft(UUID id,PolicyDraft draft,long version,org.springframework.security.core.Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"CONSENT_WRITE");Policy existing=repository.policy(id).orElseThrow(()->new ConsentException("CONSENT_POLICY_NOT_FOUND"));validate(existing.type(),draft);
        if(existing.status().equals("DRAFT")&&existing.version()!=version)throw new ConsentException("CONSENT_POLICY_VERSION_CONFLICT");
        if(repository.updateDraft(id,version,draft)!=1)throw new ConsentException("CONSENT_POLICY_VERSION_CONFLICT");
        Policy updated=repository.policy(id).orElseThrow(()->new ConsentException("CONSENT_POLICY_NOT_FOUND"));event(actor,metadata,"CONSENT_POLICY_DRAFT_UPDATED","CONSENT_POLICY",id,Map.of("version",updated.version()));return updated;
    }

    @Transactional
    public Policy publish(UUID id,long version,org.springframework.security.core.Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"CONSENT_WRITE");Policy result=repository.publish(id,version,actor).orElseThrow(()->new ConsentException("CONSENT_POLICY_VERSION_CONFLICT"));
        event(actor,metadata,"CONSENT_POLICY_PUBLISHED","CONSENT_POLICY",id,Map.of("type",result.type(),"revision",result.revision()));return result;
    }

    @Transactional(readOnly=true)
    public StudentConsents studentConsents(UUID studentId,org.springframework.security.core.Authentication auth){
        require(auth,"CONSENT_READ");if(!repository.studentExists(studentId))throw new ConsentException("STUDENT_NOT_FOUND");
        List<ConsentTypeStatus> found=repository.studentConsents(studentId);HashSet<String> foundTypes=new HashSet<>();found.forEach(status->foundTypes.add(status.type()));List<ConsentTypeStatus> values=new ArrayList<>(found);
        for(String type:List.of("PERSONAL_DATA_REQUIRED","MEDIA_PUBLICATION","PORTRAIT","OPTIONAL_NOTIFICATION"))if(!foundTypes.contains(type))values.add(new ConsentTypeStatus(type,null,null,"POLICY_UNAVAILABLE",null,new Uses(0,0),List.of()));
        values.sort(java.util.Comparator.comparing(ConsentTypeStatus::type));return new StudentConsents(studentId,values);
    }

    @Transactional(readOnly=true)
    public EvidenceUrl evidenceUrl(UUID consentId,org.springframework.security.core.Authentication auth){
        require(auth,"CONSENT_READ");StudentConsent consent=repository.consent(consentId).orElseThrow(()->new ConsentException("CONSENT_NOT_FOUND"));
        if(consent.evidenceAssetId()==null)throw new ConsentException("CONSENT_EVIDENCE_NOT_FOUND");
        String key=repository.privateEvidenceStorageKey(consentId).orElseThrow(()->new ConsentException("CONSENT_EVIDENCE_STORAGE_UNAVAILABLE"));
        try{return new EvidenceUrl(evidenceStorage.signedUrl(key,60),OffsetDateTime.now(clock).plusSeconds(60));}
        catch(RuntimeException exception){throw new ConsentException("CONSENT_EVIDENCE_STORAGE_UNAVAILABLE");}
    }

    @Transactional
    public StudentConsent collect(UUID studentId,CollectConsent request,UUID key,org.springframework.security.core.Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"CONSENT_WRITE");if(request==null||request.policyId()==null||request.guardianContactId()==null||!METHODS.contains(request.method()))fail("VALIDATION_ERROR");
        String scope=actor+":CONSENT_COLLECT:"+studentId;String requestHash=hash(studentId+"|"+request);
        IdempotencyClaim claim=repository.claim(scope,key,requestHash);if(!claim.claimed())return repository.consent(claim.resourceId()).orElseThrow(()->new ConsentException("CONSENT_NOT_FOUND"));
        if(!repository.studentExists(studentId))throw new ConsentException("STUDENT_NOT_FOUND");if(!repository.guardianBelongsTo(request.guardianContactId(),studentId))throw new ConsentException("CONSENT_GUARDIAN_MISMATCH");
        Policy requested=repository.policy(request.policyId()).orElseThrow(()->new ConsentException("CONSENT_POLICY_NOT_PUBLISHED"));Policy policy=repository.currentPublishedPolicy(requested.id(),requested.type()).orElseThrow(()->new ConsentException("CONSENT_POLICY_NOT_PUBLISHED"));
        if(policy.evidenceRequired()&&request.evidenceAssetId()==null)throw new ConsentException("CONSENT_EVIDENCE_REQUIRED");
        if(request.evidenceAssetId()!=null&&!repository.privateReadyEvidence(request.evidenceAssetId()))throw new ConsentException("CONSENT_EVIDENCE_PRIVATE_REQUIRED");
        OffsetDateTime consentedAt=request.consentedAt()==null?OffsetDateTime.now(clock):request.consentedAt();if(consentedAt.isAfter(OffsetDateTime.now(clock).plusSeconds(2)))throw new ConsentException("CONSENT_DATE_FUTURE");
        if(repository.studentConsents(studentId).stream().anyMatch(c->c.type().equals(policy.type())&&c.currentConsent()!=null&&c.currentConsent().policyId().equals(policy.id())&&"ACTIVE".equals(c.currentConsent().status())))throw new ConsentException("CONSENT_ACTIVE_EXISTS");
        UUID id=repository.insertConsent(studentId,policy,request,actor,consentedAt);repository.complete(scope,key,id,201);StudentConsent result=repository.consent(id).orElseThrow(()->new ConsentException("CONSENT_NOT_FOUND"));
        event(actor,metadata,"STUDENT_CONSENT_COLLECTED","STUDENT_CONSENT",id,Map.of("studentId",studentId.toString(),"policyType",policy.type(),"method",request.method()));return result;
    }

    @Transactional
    public StudentConsent revoke(UUID id,RevokeRequest request,UUID key,org.springframework.security.core.Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"CONSENT_WRITE");if(request==null||request.version()<0||request.reason()==null||request.reason().trim().length()<5||request.reason().trim().length()>300)fail("VALIDATION_ERROR");
        StudentConsent before=repository.consent(id).orElseThrow(()->new ConsentException("CONSENT_NOT_FOUND"));String scope=actor+":CONSENT_REVOKE:"+id;String requestHash=hash(id+"|"+request.version()+"|"+request.reason().trim());
        IdempotencyClaim claim=repository.claim(scope,key,requestHash);if(!claim.claimed())return repository.consent(claim.resourceId()).orElseThrow(()->new ConsentException("CONSENT_NOT_FOUND"));
        if(!repository.revoke(id,request.version(),request.reason().trim(),actor))throw new ConsentException("CONSENT_VERSION_CONFLICT");repository.complete(scope,key,id,200);
        StudentConsent result=repository.consent(id).orElseThrow(()->new ConsentException("CONSENT_NOT_FOUND"));event(actor,metadata,"STUDENT_CONSENT_REVOKED","STUDENT_CONSENT",id,Map.of("policyType",before.type()));return result;
    }

    public int expire(int limit){return repository.expireConsents(Math.min(Math.max(limit,1),500));}

    private void validate(String type,PolicyDraft draft){try{ConsentPolicyValidator.validate(type,draft==null?null:new ConsentPolicyValidator.PolicyDraft(draft.title(),draft.body(),draft.required(),draft.validDays(),draft.evidenceRequired()));}catch(IllegalArgumentException exception){throw new ConsentException(exception.getMessage());}}
    private void event(UUID actor,RequestMetadata m,String action,String target,UUID id,Map<String,Object> details){audit.record(new Event(clock.instant(),m.requestId(),"MGT-CONSENT-MANAGE","OPERATION","ADMIN",actor,null,action,target,id,"SUCCESS",null,m.ipAddress(),m.userAgent(),details));}
    private static UUID require(org.springframework.security.core.Authentication auth,String permission){if(auth==null||auth.getAuthorities().stream().noneMatch(a->permission.equals(a.getAuthority())))throw new ConsentException(permission+"_DENIED");try{return UUID.fromString(auth.getName());}catch(Exception exception){throw new ConsentException(permission+"_DENIED");}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException(exception);}}
    private static void fail(String code){throw new ConsentException(code);}

    public static final class ConsentException extends RuntimeException{private final String code;public ConsentException(String code){super(code);this.code=code;}public String code(){return code;}}
}
