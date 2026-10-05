package com.ramiart.admin.makeup.application;

import static com.ramiart.admin.makeup.application.MakeupModels.*;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.auth.application.AdminReauthenticationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MakeupService {
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "RESERVED", "COMPLETED", "EXPIRED", "WAIVED");
    private final MakeupRepository repository;
    private final AuditRecorder audit;
    private final AdminReauthenticationService reauthentication;
    public MakeupService(MakeupRepository repository, AuditRecorder audit, AdminReauthenticationService reauthentication) { this.repository = repository; this.audit = audit; this.reauthentication = reauthentication; }

    @Transactional(readOnly = true)
    public CasePage list(String status, LocalDate from, LocalDate to, UUID studentId, int page, int size, Authentication auth) {
        require(auth, "MAKEUP_READ");
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if ((normalized != null && !STATUSES.contains(normalized)) || page < 0 || size < 1 || size > 100
                || (from != null && to != null && (from.isAfter(to) || from.plusDays(92).isBefore(to))))
            throw new MakeupException("VALIDATION_ERROR");
        return repository.list(normalized, from, to, studentId, page, size);
    }
    @Transactional(readOnly = true)
    public Detail detail(UUID id, Authentication auth) { require(auth, "MAKEUP_READ"); return getDetail(id); }
    @Transactional(readOnly = true)
    public CandidatePage candidates(UUID caseId, LocalDate from, LocalDate to, Authentication auth) {
        require(auth, "MAKEUP_READ");
        if (from == null || to == null || from.isAfter(to) || from.plusDays(31).isBefore(to)) throw new MakeupException("VALIDATION_ERROR");
        getDetail(caseId);
        return new CandidatePage(repository.candidates(caseId, from, to), from, to);
    }

    @Transactional
    public ReservationResult reserve(UUID id, Reservation body, UUID key, Authentication auth, Metadata metadata) {
        UUID actor = require(auth,"MAKEUP_WRITE");
        if (body == null || key == null || body.caseVersion() < 0 || body.sessionVersion() < 0) throw new MakeupException("VALIDATION_ERROR");
        String scope=actor+":MAKEUP_RESERVE:"+id, requestHash=hash(id+":"+body.sessionId()+":"+body.caseVersion()+":"+body.sessionVersion());
        if (!repository.claim(scope,key,requestHash)) { verifyReplay(scope,key,requestHash); return new ReservationResult(getDetail(id),false); }
        repository.findCase(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));
        var session=repository.lockSession(body.sessionId()).orElseThrow(()->new MakeupException("MAKEUP_SESSION_INCOMPATIBLE"));
        var item=repository.lockCase(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));
        if(item.version()!=body.caseVersion()) throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        if(!"AVAILABLE".equals(item.status())) throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        if(item.expiresOn().isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")))) throw new MakeupException("MAKEUP_EXPIRED");
        if(session.version()!=body.sessionVersion()) throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        if(!"OPEN".equals(session.status())||!session.startsAt().isAfter(java.time.OffsetDateTime.now(ZoneId.of("Asia/Seoul")))) throw new MakeupException("MAKEUP_SESSION_INCOMPATIBLE");
        if(!item.courseId().equals(session.courseId())) throw new MakeupException("MAKEUP_SESSION_INCOMPATIBLE");
        int changed=repository.reserve(id,item,session,actor);
        if(changed==0) throw new MakeupException("MAKEUP_CAPACITY_FULL");
        if(changed<0) throw new MakeupException("MAKEUP_STUDENT_TIME_CONFLICT");
        record(actor,metadata,"MAKEUP_RESERVED",id,Map.of("sessionId",session.id().toString()));
        repository.complete(scope,key,id,201);
        return new ReservationResult(getDetail(id),true);
    }

    @Transactional
    public Detail cancel(UUID id, VersionedReason body, UUID key, Authentication auth, Metadata metadata) {
        UUID actor=require(auth,"MAKEUP_WRITE"); validateReason(body,key);
        String scope=actor+":MAKEUP_CANCEL:"+id, requestHash=hash(id+":"+body.version()+":"+body.reason());
        if(!repository.claim(scope,key,requestHash)){verifyReplay(scope,key,requestHash);return getDetail(id);}
        var snapshot=repository.findCase(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));
        if(snapshot.reservedSessionId()==null)throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        var session=repository.lockSession(snapshot.reservedSessionId()).orElseThrow(()->new MakeupException("MAKEUP_SESSION_CLOSED"));
        var item=repository.lockCase(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));
        if(item.version()!=body.version())throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        if(!"RESERVED".equals(item.status()))throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        if(!"OPEN".equals(session.status())||!session.startsAt().isAfter(java.time.OffsetDateTime.now(ZoneId.of("Asia/Seoul"))))throw new MakeupException("MAKEUP_SESSION_CLOSED");
        if(repository.cancelReservation(id,item,actor)!=1)throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        record(actor,metadata,"MAKEUP_RESERVATION_CANCELLED",id,Map.of("reason",body.reason().trim(),"sessionId",item.reservedSessionId().toString()));
        repository.complete(scope,key,id,200);return getDetail(id);
    }

    @Transactional
    public Detail waive(UUID id, VersionedReason body, UUID key, Authentication auth, Metadata metadata) {
        UUID actor=require(auth,"MAKEUP_WRITE");validateReason(body,key);
        String scope=actor+":MAKEUP_WAIVE:"+id, requestHash=hash(id+":"+body.version()+":"+body.reason());
        if(!repository.claim(scope,key,requestHash)){verifyReplay(scope,key,requestHash);return getDetail(id);}
        var item=repository.lockCase(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));
        if(item.version()!=body.version())throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        if(repository.waive(id,body.version(),body.reason(),actor)!=1)throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        record(actor,metadata,"MAKEUP_WAIVED",id,Map.of("reason",body.reason().trim()));repository.complete(scope,key,id,200);return getDetail(id);
    }

    @Transactional
    public int expireAvailableCases(LocalDate today) {
        List<MakeupRepository.ExpiredCase> expired=repository.expireAvailableCases(today,500);
        for(var item:expired) audit.record(new Event(java.time.Instant.now(),"system-makeup-expiration","MGT-MAKEUP-MANAGE","OPERATION","SYSTEM",null,"시스템",
                "MAKEUP_CASE_EXPIRED","MAKEUP_CASE",item.id(),"SUCCESS",null,null,null,Map.of()));
        return expired.size();
    }

    @Transactional
    public Detail extend(UUID id, Extension body, UUID key, String sessionCookie, Authentication auth, Metadata metadata) {
        UUID actor=require(auth,"ADMIN_ACCOUNT_WRITE");
        if(body==null||key==null||body.version()<0||body.newExpiresOn()==null||body.reason()==null||body.reason().trim().isEmpty()||body.reason().trim().length()>200)
            throw new MakeupException("VALIDATION_ERROR");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        if(!body.newExpiresOn().isAfter(today)||body.newExpiresOn().isAfter(today.plusDays(180)))throw new MakeupException("VALIDATION_ERROR");
        String scope=actor+":MAKEUP_EXTEND:"+id, requestHash=hash(id+":"+body.version()+":"+body.newExpiresOn()+":"+body.reason()+":"+body.reauthToken());
        if(!repository.claim(scope,key,requestHash)){verifyReplay(scope,key,requestHash);return getDetail(id);}
        var item=repository.lockCase(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));
        if(item.version()!=body.version())throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        try { reauthentication.consume(body.reauthToken(),sessionCookie,"MAKEUP_EXTENSION",auth); }
        catch (AdminReauthenticationService.ReauthenticationException exception) { throw new MakeupException(exception.code()); }
        if(repository.extend(id,body.version(),body.newExpiresOn(),body.reason(),actor)!=1)throw new MakeupException("MAKEUP_VERSION_CONFLICT");
        record(actor,metadata,"MAKEUP_EXTENDED",id,Map.of("newExpiresOn",body.newExpiresOn().toString(),"reason",body.reason().trim()));
        repository.complete(scope,key,id,200);return getDetail(id);
    }

    private Detail getDetail(UUID id){return repository.detail(id).orElseThrow(()->new MakeupException("MAKEUP_CASE_NOT_FOUND"));}
    private void verifyReplay(String scope,UUID key,String hash){if(repository.hash(scope,key).filter(hash::equals).isEmpty())throw new MakeupException("IDEMPOTENCY_KEY_REUSED");}
    private void record(UUID actor,Metadata m,String action,UUID id,Map<String,Object> details){
        audit.record(new Event(java.time.Instant.now(),m.requestId(),"MGT-MAKEUP-MANAGE","OPERATION","ADMIN",actor,null,
                action,"MAKEUP_CASE",id,"SUCCESS",null,m.ipAddress(),m.userAgent(),details));
    }
    private static void validateReason(VersionedReason body,UUID key){if(body==null||key==null||body.version()<0||body.reason()==null||body.reason().trim().isEmpty()||body.reason().trim().length()>200)throw new MakeupException("VALIDATION_ERROR");}
    private static UUID require(Authentication auth,String permission){if(auth==null||auth.getAuthorities().stream().noneMatch(value->permission.equals(value.getAuthority())))throw new MakeupException(permission+"_DENIED");try{return UUID.fromString(auth.getName());}catch(RuntimeException e){throw new MakeupException("SESSION_REQUIRED");}}
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}}
    public record Metadata(String requestId,String ipAddress,String userAgent){}
    public static final class MakeupException extends RuntimeException{private final String code;public MakeupException(String code){super(code);this.code=code;}public String code(){return code;}}
}
