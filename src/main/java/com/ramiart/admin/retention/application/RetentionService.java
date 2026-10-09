package com.ramiart.admin.retention.application;

import static com.ramiart.admin.retention.application.RetentionModels.*;
import com.ramiart.admin.auth.application.AdminReauthenticationService;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
    private static final Map<String,String> RULES=Map.of(
            "INQUIRY","retention_expires_at", "STUDENT_PRIVATE","terminal status + five years; referenced history excluded",
            "CONSENT_EVIDENCE","revocation/expiry + five years", "TRANSFER_FILE","expires_at",
            "NOTIFICATION_PAYLOAD","terminal completion + one year");
    private static final String POLICY="2026-10-09-v1";
    private final RetentionRepository repository;
    private final AdminReauthenticationService reauth;
    private final AuditRecorder audit;
    private final Clock clock;
    public RetentionService(RetentionRepository repository,AdminReauthenticationService reauth,AuditRecorder audit,Clock clock){
        this.repository=repository;this.reauth=reauth;this.audit=audit;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Policies policies(Authentication auth){
        require(auth,"RETENTION_READ");return new Policies(RULES.keySet().stream().sorted()
                .map(d->new Policy(d,POLICY,RULES.get(d),repository.latest(d).map(StoredRun::value).orElse(null))).toList());
    }
    @Transactional
    public HoldPage holds(String type,String status,String cursor,int size,Authentication auth){
        require(auth,"RETENTION_READ");if(size<1||size>100||type!=null&&!TYPES.contains(type)||status!=null&&!Set.of("ACTIVE","RELEASED","EXPIRED").contains(status))fail("VALIDATION_ERROR");
        UUID after=null;if(cursor!=null)try{after=UUID.fromString(cursor);}catch(RuntimeException e){fail("INVALID_QUERY");}
        repository.lockGovernance(clock.instant());List<Hold> found=repository.holds(type,status,after,size+1);boolean more=found.size()>size;
        List<Hold> selected=more?found.subList(0,size):found;
        return new HoldPage(selected,more?selected.getLast().id().toString():null,more);
    }
    private static final Set<String> TYPES=Set.of("INQUIRY","STUDENT","STUDENT_CONSENT","DATA_TRANSFER_JOB","NOTIFICATION_MESSAGE");
    @Transactional
    public Hold createHold(HoldWrite write,Authentication auth,Metadata meta){
        UUID actor=require(auth,"RETENTION_EXECUTE");
        if(write==null||write.targetType()==null||!TYPES.contains(write.targetType())||write.targetId()==null||!reasonValid(write.reason())||write.endsAt()!=null&&!write.endsAt().isAfter(clock.instant()))fail("VALIDATION_ERROR");
        repository.lockGovernance(clock.instant());if(!repository.targetExists(write.targetType(),write.targetId()))fail("RETENTION_TARGET_NOT_FOUND");
        Hold result=repository.createHold(write,actor,clock.instant());event(actor,meta,"RETENTION_HOLD_CREATED",result.id(),Map.of("targetType",write.targetType()));return result;
    }
    @Transactional
    public Hold releaseHold(UUID id,String reason,Authentication auth,Metadata meta){
        UUID actor=require(auth,"RETENTION_EXECUTE");if(id==null||!reasonValid(reason))fail("VALIDATION_ERROR");
        repository.lockGovernance(clock.instant());Hold result=repository.releaseHold(id,reason.trim(),actor,clock.instant());
        event(actor,meta,"RETENTION_HOLD_RELEASED",id,Map.of());return result;
    }
    @Transactional
    public Preview preview(PreviewWrite write,Authentication auth){
        require(auth,"RETENTION_READ");if(write==null||write.domain()==null||!RULES.containsKey(write.domain())||write.cutoffAt()==null||write.cutoffAt().isAfter(clock.instant()))fail("VALIDATION_ERROR");
        repository.lockGovernance(clock.instant());List<Candidate> candidates=repository.candidates(write.domain(),write.cutoffAt(),clock.instant(),clock.instant());
        if(candidates.size()>10000)fail("RETENTION_PREVIEW_TOO_LARGE");
        StoredRun stored=repository.createPreview(write.domain(),POLICY,write.cutoffAt(),candidates,fingerprint(candidates),clock.instant());Run r=stored.value();
        return new Preview(r.previewVersion(),r.domain(),POLICY,write.cutoffAt(),r.candidateCount(),r.holdExcludedCount(),r.referenceExcludedCount(),r.createdAt().plus(Duration.ofHours(24)));
    }
    @Transactional
    public Run execute(RunWrite request,UUID key,String cookie,Authentication auth,Metadata meta){
        UUID actor=require(auth,"RETENTION_EXECUTE");if(request==null||request.previewVersion()==null||key==null)fail("VALIDATION_ERROR");
        repository.lockGovernance(clock.instant());if(!repository.owner(actor))fail("RETENTION_OWNER_REAUTH_REQUIRED");
        String hash=digest(request.previewVersion()+"|"+request.confirmation());
        Optional<UUID> replay=repository.replay(actor,key,hash);if(replay.isPresent())return repository.findRun(replay.get(),false).orElseThrow().value();
        StoredRun stored=repository.findPreview(request.previewVersion(),true).orElseThrow(()->new RetentionException("RETENTION_PREVIEW_NOT_FOUND"));
        Run run=stored.value();if(!"파기 실행".equals(request.confirmation()))fail("RETENTION_CONFIRMATION_INVALID");
        if(!POLICY.equals(stored.policyVersion())||!run.createdAt().plus(Duration.ofHours(24)).isAfter(clock.instant()))fail("RETENTION_PREVIEW_STALE");
        if(!Set.of("PREVIEWED","PARTIAL","FAILED").contains(run.status()))fail("RETENTION_RUN_IN_PROGRESS");
        if(!stored.fingerprint().equals(fingerprint(repository.candidates(run.domain(),stored.cutoffAt(),run.createdAt(),clock.instant()))))fail("RETENTION_PREVIEW_STALE");
        if(repository.processing(run.domain()))fail("RETENTION_RUN_IN_PROGRESS");
        try{reauth.consume(request.reauthToken(),cookie,"RETENTION_EXECUTION",auth);}
        catch(AdminReauthenticationService.ReauthenticationException e){throw new RetentionException("RETENTION_OWNER_REAUTH_REQUIRED");}
        repository.queue(stored,actor,key,hash,clock.instant());event(actor,meta,"RETENTION_RUN_APPROVED",run.id(),Map.of("domain",run.domain(),"candidateCount",run.candidateCount()));
        return repository.findRun(run.id(),false).orElseThrow().value();
    }
    @Transactional(readOnly=true)
    public Run run(UUID id,Authentication auth){require(auth,"RETENTION_READ");return repository.findRun(id,false).orElseThrow(()->new RetentionException("RETENTION_RUN_NOT_FOUND")).value();}
    @Transactional
    public boolean processNext(){
        repository.lockGovernance(clock.instant());Optional<StoredRun> claimed=repository.claimNext();if(claimed.isEmpty())return false;
        StoredRun stored=claimed.get();Run run=stored.value();
        List<Candidate> snapshot=repository.candidates(run.domain(),stored.cutoffAt(),run.createdAt(),clock.instant());
        if(!stored.fingerprint().equals(fingerprint(snapshot))||!repository.owner(stored.actor())){
            repository.finish(run.id(),run.processedCount(),0,Map.of("RETENTION_PREVIEW_STALE",1),"FAILED",stored.fingerprint(),clock.instant());return true;
        }
        List<Candidate> eligible=snapshot.stream().filter(c->!c.held()&&!c.referenced()).toList();
        int remaining=run.candidateCount()-run.holdExcludedCount()-run.referenceExcludedCount()-run.processedCount();
        if(eligible.size()>remaining){repository.finish(run.id(),run.processedCount(),0,Map.of("RETENTION_PREVIEW_STALE",1),"FAILED",stored.fingerprint(),clock.instant());return true;}
        int processed=run.processedCount();Map<String,Integer> errors=new LinkedHashMap<>();
        for(Candidate candidate:eligible.stream().limit(100).toList()){
            try{repository.purge(run.domain(),candidate,clock.instant());processed++;}
            catch(RuntimeException exception){errors.merge("RETENTION_ITEM_FAILED",1,Integer::sum);}
        }
        String status=!errors.isEmpty()?(processed>0?"PARTIAL":"FAILED"):eligible.size()>100?"PROCESSING":"COMPLETED";
        repository.finish(run.id(),processed,errors.values().stream().mapToInt(Integer::intValue).sum(),errors,status,
                fingerprint(repository.candidates(run.domain(),stored.cutoffAt(),run.createdAt(),clock.instant())),clock.instant());
        if(!"PROCESSING".equals(status))audit.record(new Event(clock.instant(),"retention-worker","MGT-RETENTION-EXECUTE","OPERATION","ADMIN",stored.actor(),null,"RETENTION_RUN_FINISHED","RETENTION_RUN",run.id(),"SUCCESS",null,null,null,Map.of("domain",run.domain(),"processedCount",processed,"failedCount",errors.values().stream().mapToInt(Integer::intValue).sum())));
        return true;
    }
    private void event(UUID actor,Metadata meta,String action,UUID id,Map<String,Object> details){audit.record(new Event(clock.instant(),meta.requestId(),"MGT-RETENTION-EXECUTE","OPERATION","ADMIN",actor,null,action,"RETENTION_RUN",id,"SUCCESS",null,meta.ipAddress(),meta.userAgent(),details));}
    private static String fingerprint(List<Candidate> values){return digest(values.stream().map(c->c.id()+"|"+c.held()+"|"+c.referenced()+"|"+c.version()+"|"+Objects.toString(c.storageKey(),"")).sorted().collect(java.util.stream.Collectors.joining("\n")));}
    private static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static boolean reasonValid(String s){return s!=null&&s.trim().length()>=10&&s.trim().length()<=500;}
    private static UUID require(Authentication a,String p){if(a==null||a.getAuthorities().stream().noneMatch(x->p.equals(x.getAuthority())))fail(p+"_DENIED");try{return UUID.fromString(a.getName());}catch(Exception e){throw new RetentionException("SESSION_REQUIRED");}}
    private static void fail(String c){throw new RetentionException(c);}
}
