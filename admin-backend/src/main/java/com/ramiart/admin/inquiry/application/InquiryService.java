package com.ramiart.admin.inquiry.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.inquiry.application.InquiryModels.*;
import com.ramiart.admin.inquiry.application.InquiryRepository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InquiryService {
    private static final Set<String> STATUSES=Set.of("RECEIVED","CONTACTING","COMPLETED","UNREACHABLE");
    private final InquiryRepository repository;
    private final InquiryDataProtector protector;
    private final InquiryRateLimiter rateLimiter;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String currentPolicyVersion;
    private final ZoneId studioZone;

    public InquiryService(InquiryRepository repository, InquiryDataProtector protector, InquiryRateLimiter rateLimiter,
            AuditRecorder audit, Clock clock,
            @Value("${admin.inquiry.consent-policy-version:${ADMIN_INQUIRY_CONSENT_POLICY_VERSION:privacy-2026-07}}") String policy,
            @Value("${admin.inquiry.studio-zone:${ADMIN_STUDIO_ZONE:Asia/Seoul}}") String zone) {
        this.repository=repository; this.protector=protector; this.rateLimiter=rateLimiter; this.audit=audit;
        this.clock=clock; this.currentPolicyVersion=policy; this.studioZone=ZoneId.of(zone);
    }

    @Transactional
    public Accepted submit(PublicSubmission raw, UUID key, RequestMetadata meta) {
        PublicSubmission command=normalize(raw);
        validateSubmission(command);
        if (!rateLimiter.consume(meta.ipAddress(),command.phone())) throw new InquiryException("INQUIRY_RATE_LIMITED");
        if (!command.company().isEmpty()) return new Accepted(true);
        String requestHash=digest(command.toString());
        Claim claim=repository.claim("INQUIRY_PUBLIC_SUBMIT",key,requestHash);
        if (!claim.claimed()) return new Accepted(true);
        UUID id=UUID.randomUUID(); Instant now=clock.instant();
        repository.insert(id,protector.protect(command.name()),protector.hash("name:"+normalizeName(command.name())),
                protector.protect(command.phone()),protector.hash("phone:"+command.phone()),last4(command.phone()),
                command.interestedCourseId(),protector.protect(command.message()),command.consentPolicyVersion(),now);
        recordAudit(meta,"MGT-INQUIRY-SUBMIT","ANONYMOUS",null,"INQUIRY_SUBMITTED",id,
                Map.of("courseSelected",command.interestedCourseId()!=null));
        repository.complete("INQUIRY_PUBLIC_SUBMIT",key,id,202);
        return new Accepted(true);
    }

    @Transactional(readOnly=true)
    public InquiryPage list(String keyword,List<UUID> courseIds,List<String> statuses,LocalDate from,LocalDate to,
            String readState,int page,int size) {
        LocalDate today=LocalDate.now(clock.withZone(studioZone));
        var q=validateQuery(keyword,courseIds,statuses,from,to,readState,page,size,today);
        String keywordHash=null;
        if (q.keyword()!=null) {
            String normalized=normalizePhoneOrNull(q.keyword());
            keywordHash=protector.hash(normalized==null?"name:"+normalizeName(q.keyword()):"phone:"+normalized);
        }
        Instant stale=clock.instant().minus(3,ChronoUnit.DAYS);
        PageRecords rows=repository.findPage(q,keywordHash,stale);
        List<InquirySummary> items=rows.items().stream().map(row->new InquirySummary(row.id(),protector.reveal(row.nameCiphertext()),
                "***-****-"+row.phoneLast4(),course(row.course()),row.status(),row.readAt()!=null,row.receivedAt(),
                row.lastActivityAt(),isOpen(row.status())&&!row.receivedAt().isAfter(stale))).toList();
        return new InquiryPage(q.page(),q.size(),rows.total(),(int)Math.ceil((double)rows.total()/q.size()),
                new Summary(rows.unread(),rows.stale()),items);
    }

    @Transactional(readOnly=true)
    public List<CourseOption> courseOptions() {
        return repository.findCourseOptions().stream()
                .map(course -> new CourseOption(course.id(),course.name(),course.active())).toList();
    }

    @Transactional(readOnly=true)
    public InquiryDetail detail(UUID id) { return toDetail(require(id),repository.findActivities(id)); }

    @Transactional
    public ReadReceipt markRead(UUID id,ReadReceiptWrite body,UUID actor,UUID key,RequestMetadata meta) {
        if (body==null||body.inquiryVersion()<0) throw new InquiryException("INQUIRY_INVALID");
        String scope="INQUIRY_READ:"+id; Claim claim=repository.claim(scope,key,digest(body.toString()));
        InquiryRecord current=require(id);
        if (!claim.claimed()) return receipt(require(id));
        if (current.readAt()==null) {
            if (repository.markRead(id,body.inquiryVersion(),actor,clock.instant())==0) {
                InquiryRecord latest=require(id);
                if (latest.readAt()==null) throw new InquiryException("INQUIRY_VERSION_CONFLICT");
            } else recordAudit(meta,"MGT-INQUIRY-PROCESS","ADMIN",actor,"INQUIRY_READ",id,
                    Map.of("previousVersion",body.inquiryVersion()));
        }
        repository.complete(scope,key,id,200);
        return receipt(require(id));
    }

    @Transactional
    public ActivityCreated addActivity(UUID id,ActivityWrite raw,UUID actor,UUID key,RequestMetadata meta) {
        ActivityWrite body=normalize(raw);
        if (body==null||body.inquiryVersion()<0||body.note()==null||body.note().length()<5||body.note().length()>1000)
            throw new InquiryException("INQUIRY_NOTE_REQUIRED");
        if (!STATUSES.contains(body.toStatus())) throw new InquiryException("INQUIRY_TRANSITION_DENIED");
        String scope="INQUIRY_ACTIVITY:"+id; Claim claim=repository.claim(scope,key,digest(body.toString()));
        if (!claim.claimed()) return created(id,claim.resourceId());
        InquiryRecord current=require(id);
        if (!allowed(current.status()).contains(body.toStatus())) throw new InquiryException("INQUIRY_TRANSITION_DENIED");
        if (repository.transition(id,body.inquiryVersion(),current.status(),body.toStatus())==0)
            throw new InquiryException("INQUIRY_VERSION_CONFLICT");
        UUID activityId=repository.insertActivity(id,current.status(),body.toStatus(),protector.protect(body.note()),
                actor,current.version()+1,clock.instant());
        recordAudit(meta,"MGT-INQUIRY-PROCESS","ADMIN",actor,"INQUIRY_STATUS_CHANGED",id,
                Map.of("fromStatus",current.status(),"toStatus",body.toStatus(),"previousVersion",body.inquiryVersion()));
        repository.complete(scope,key,activityId,201);
        return created(id,activityId);
    }

    private ActivityCreated created(UUID id,UUID activityId) {
        InquiryRecord latest=require(id); ActivityView activity=toActivity(repository.findActivity(activityId)
                .orElseThrow(()->new InquiryException("INQUIRY_NOT_FOUND")));
        return new ActivityCreated(new InquiryState(id,latest.version(),latest.status(),latest.readAt()!=null,
                latest.lastActivityAt(),allowed(latest.status())),activity);
    }
    private InquiryRecord require(UUID id){ return repository.find(id).orElseThrow(()->new InquiryException("INQUIRY_NOT_FOUND")); }
    private InquiryDetail toDetail(InquiryRecord row,List<ActivityRecord> activities){ String phone=protector.reveal(row.phoneCiphertext());
        return new InquiryDetail(row.id(),row.version(),protector.reveal(row.nameCiphertext()),phone,displayPhone(phone),course(row.course()),
                protector.reveal(row.messageCiphertext()),row.status(),row.readAt()!=null,row.readAt(),admin(row.readById(),row.readByName()),
                new ConsentView(row.consentPolicyVersion(),row.consentedAt()),new NotificationView(row.notificationStatus(),row.notificationAttemptedAt()),
                allowed(row.status()),activities.stream().map(this::toActivity).toList()); }
    private ActivityView toActivity(ActivityRecord row){ return new ActivityView(row.id(),row.fromStatus(),row.toStatus(),
            protector.reveal(row.noteCiphertext()),admin(row.createdById(),row.createdByName()),row.createdAt()); }
    private ReadReceipt receipt(InquiryRecord row){ return new ReadReceipt(row.version(),row.readAt()!=null,row.readAt(),admin(row.readById(),row.readByName())); }
    private static AdminView admin(UUID id,String name){ return id==null?null:new AdminView(id,name); }
    private static CourseView course(CourseRecord row){ return row==null?null:new CourseView(row.id(),row.name(),row.active()); }
    private static List<String> allowed(String status){ return switch(status){ case "RECEIVED"->List.of("CONTACTING"); case "CONTACTING"->List.of("COMPLETED","UNREACHABLE"); default->List.of(); }; }
    private static boolean isOpen(String status){ return status.equals("RECEIVED")||status.equals("CONTACTING"); }

    private PublicSubmission normalize(PublicSubmission value){ if(value==null)throw new InquiryException("INQUIRY_INVALID");
        return new PublicSubmission(trim(value.name()),normalizePhone(value.phone()),value.interestedCourseId(),trim(value.message()),
                value.privacyConsent(),trim(value.consentPolicyVersion()),trim(value.company())==null?"":trim(value.company())); }
    private void validateSubmission(PublicSubmission v){
        if(v.name()==null||v.name().isEmpty()||v.name().length()>50||v.message()==null||v.message().isEmpty()||v.message().length()>2000)
            throw new InquiryException("INQUIRY_INVALID");
        if(!Boolean.TRUE.equals(v.privacyConsent()))throw new InquiryException("PRIVACY_CONSENT_REQUIRED");
        if(!currentPolicyVersion.equals(v.consentPolicyVersion()))throw new InquiryException("CONSENT_POLICY_VERSION_INVALID");
        if(v.interestedCourseId()!=null&&!repository.activeCourseExists(v.interestedCourseId()))throw new InquiryException("INQUIRY_INVALID");
    }
    private InquiryQuery validateQuery(String keyword,List<UUID> courses,List<String> statuses,LocalDate from,LocalDate to,String readState,int page,int size,LocalDate today){
        String k=trim(keyword); List<UUID> c=courses==null?List.of():List.copyOf(courses); List<String> s=statuses==null||statuses.isEmpty()?List.of("RECEIVED","CONTACTING"):statuses.stream().map(String::toUpperCase).toList();
        LocalDate f=from==null?today.minusDays(90):from, t=to==null?today:to; String r=readState==null?"ALL":readState.toUpperCase(Locale.ROOT);
        if((k!=null&&(k.isEmpty()||k.length()>50))||c.size()>20||!STATUSES.containsAll(s)||!Set.of("ALL","READ","UNREAD").contains(r)||page<0||!Set.of(10,20,50).contains(size)||f.isAfter(t)||f.plusYears(3).isBefore(t)||!repository.coursesExist(c))throw new InquiryException("INQUIRY_QUERY_INVALID");
        return new InquiryQuery(k,c,s,f.atStartOfDay(studioZone).toInstant(),t.plusDays(1).atStartOfDay(studioZone).toInstant(),r,page,size);
    }
    private void recordAudit(RequestMetadata m,String task,String actorType,UUID actor,String action,UUID target,Map<String,Object> details){
        audit.record(new Event(clock.instant(),m.requestId(),task,"OPERATION",actorType,actor,null,action,"INQUIRY",target,"SUCCESS",null,m.ipAddress(),m.userAgent(),details)); }
    private static ActivityWrite normalize(ActivityWrite v){ return v==null?null:new ActivityWrite(v.toStatus()==null?null:v.toStatus().trim().toUpperCase(Locale.ROOT),trim(v.note()),v.inquiryVersion()); }
    private static String normalizeName(String v){ return v.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
    private static String normalizePhone(String v){ String p=normalizePhoneOrNull(v); if(p==null)throw new InquiryException("INQUIRY_INVALID"); return p; }
    private static String normalizePhoneOrNull(String v){ if(v==null)return null; String p=v.trim().replaceAll("[\\s().-]",""); if(p.startsWith("0"))p="+82"+p.substring(1); return p.matches("^\\+[1-9][0-9]{7,14}$")?p:null; }
    private static String displayPhone(String p){ return p.matches("^\\+8210[0-9]{8}$")?"010-"+p.substring(5,9)+"-"+p.substring(9):p; }
    private static String last4(String p){ return p.substring(p.length()-4); }
    private static String trim(String v){ return v==null?null:v.trim(); }
    private static String digest(String value){ try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    public record RequestMetadata(String requestId,String ipAddress,String userAgent){}
}
