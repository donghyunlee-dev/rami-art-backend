package com.ramiart.admin.notification.application;

import static com.ramiart.admin.notification.application.NotificationModels.*;
import static com.ramiart.admin.notification.application.NotificationRepository.*;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.student.application.StudentDataProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final Set<String> TYPES=Set.of("LESSON_NOTICE","PAYMENT_DUE","OVERDUE","GENERAL");
    private static final Set<String> CHANNELS=Set.of("EMAIL","SMS","KAKAO","MANUAL");
    private final NotificationRepository repository;
    private final NotificationTemplateRenderer renderer;
    private final StudentDataProtector protector;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final List<NotificationProvider> providers;

    public NotificationService(NotificationRepository repository,NotificationTemplateRenderer renderer,
            StudentDataProtector protector,AuditRecorder audit,ObjectMapper mapper,Clock clock,List<NotificationProvider> providers){
        this.repository=repository;this.renderer=renderer;this.protector=protector;this.audit=audit;this.mapper=mapper;this.clock=clock;this.providers=List.copyOf(providers);
    }

    @Transactional(readOnly=true)
    public Preview preview(DraftRequest request,Authentication auth){
        require(auth,"NOTIFICATION_SEND");DraftRequest draft=normalize(request);List<Candidate> candidates=repository.candidates(draft.recipientFilter(),draft.scheduledAt());
        if(candidates.size()>500)throw new NotificationException("NOTIFICATION_RECIPIENT_LIMIT");
        Analysis analysis=analyze(draft,candidates);if(analysis.eligible().isEmpty())throw new NotificationException("NOTIFICATION_NO_ELIGIBLE_RECIPIENT");
        OffsetDateTime expires=OffsetDateTime.now(clock).plusMinutes(5);String payload=requestHash(draft)+"|"+analysis.fingerprint()+"|"+expires.toEpochSecond();
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"."+protector.hash("notification-preview:v1:"+payload);
        List<RenderedSample> samples=analysis.eligible().stream().limit(3).map(item->new RenderedSample(mask(item.candidate().studentName()),mask(item.candidate().guardianName()),maskRendered(item.subject(),item.variables()),maskRendered(item.body(),item.variables()))).toList();
        List<String>warnings=new ArrayList<>();if(analysis.excluded()>0)warnings.add("선택 수신 동의가 없거나 만료된 대상은 제외됩니다.");if(analysis.missing()>0)warnings.add("주 보호자 연락처가 없는 대상은 제외됩니다.");
        return new Preview(analysis.eligible().size(),analysis.missing(),analysis.excluded(),analysis.deduplicated(),samples,warnings,token,expires);
    }

    @Transactional
    public QueueResult queue(QueueRequest request,UUID key,Authentication auth,RequestMetadata metadata){
        return queueInternal(request,key,auth,metadata);
    }
    private QueueResult queueInternal(QueueRequest request,UUID key,Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"NOTIFICATION_SEND");DraftRequest draft=normalize(request==null?null:new DraftRequest(request.type(),request.channel(),request.recipientFilter(),request.subjectTemplate(),request.bodyTemplate(),request.variables(),request.scheduledAt(),request.optionalNotice()));
        String scope=actor+":NOTIFICATION_QUEUE";String hash=protector.hash(requestHash(draft)+"|"+(request==null?"":String.valueOf(request.previewToken())));
        Claim claim=claim(scope,key,hash);if(!claim.claimed()){int count=repository.batchQueuedCount(claim.resourceId());return new QueueResult(claim.resourceId(),count,count);}
        Analysis analysis=analyze(draft,repository.candidates(draft.recipientFilter(),draft.scheduledAt()));if(analysis.eligible().isEmpty())throw new NotificationException("NOTIFICATION_NO_ELIGIBLE_RECIPIENT");
        verifyToken(request.previewToken(),draft,analysis.fingerprint());
        UUID batch=UUID.randomUUID();byte[] filter=protectJson(draft.recipientFilter());byte[] subjectTemplate=draft.subjectTemplate()==null?null:protect(draft.subjectTemplate());
        repository.createBatch(batch,draft,analysis.eligible().size(),analysis.missing(),analysis.excluded(),analysis.deduplicated(),analysis.fingerprint(),actor,filter,subjectTemplate,protect(draft.bodyTemplate()),protectJson(draft.variables()));
        int count=0;
        for(RenderedTarget target:analysis.eligible()){
            Candidate recipient=target.candidate();String messageScope=protector.hash(batch+"|"+actor+"|"+draft.type()+"|"+draft.channel()+"|"+target.recipientHash()+"|"+target.subject()+"|"+target.body());
            repository.createMessage(UUID.randomUUID(),batch,recipient,draft,actor,target.recipientCiphertext(),target.recipientHash(),target.recipientLast4(),target.subject()==null?null:protect(target.subject()),protect(target.body()),protectJson(target.variables()),messageScope);count++;
        }
        repository.complete(scope,key,batch,201);audit(actor,metadata,"NOTIFICATION_QUEUED","NOTIFICATION_BATCH",batch,Map.of("type",draft.type(),"channel",draft.channel(),"count",count,"optional",draft.optionalNotice()));
        return new QueueResult(batch,count,count);
    }

    @Transactional(readOnly=true)
    public MessagePage list(int page,int size,String status,String type,String channel,Authentication auth){
        require(auth,"NOTIFICATION_READ");if(page<0||size<1||size>100||status!=null&&!Set.of("QUEUED","SENDING","SENT","FAILED","CANCELLED").contains(status)||type!=null&&!TYPES.contains(type)||channel!=null&&!CHANNELS.contains(channel))throw new NotificationException("VALIDATION_ERROR");
        return new MessagePage(repository.page(page,size,status,type,channel),page,size,repository.count(status,type,channel));
    }
    @Transactional(readOnly=true)
    public MessageDetail detail(UUID id,Authentication auth){
        require(auth,"NOTIFICATION_READ");StoredMessageDetail d=repository.detail(id).orElseThrow(()->new NotificationException("NOTIFICATION_NOT_FOUND"));
        return new MessageDetail(d.message(),reveal(d.subject()),reveal(d.body()),d.recipientLast4(),d.attemptCount(),d.lastErrorCode(),d.attempts());
    }
    @Transactional
    public MessageDetail cancel(UUID id,long version,String reason,Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"NOTIFICATION_SEND");if(version<0||reason==null||reason.trim().length()<5||reason.trim().length()>200)throw new NotificationException("VALIDATION_ERROR");
        if(!repository.cancel(id,version,reason.trim(),actor))throw new NotificationException("NOTIFICATION_VERSION_CONFLICT");
        MessageDetail detail=detail(id,auth);audit(actor,metadata,"NOTIFICATION_CANCELLED","NOTIFICATION_MESSAGE",id,Map.of("version",detail.message().version()));return detail;
    }
    @Transactional
    public MessageDetail retry(UUID id,UUID key,Authentication auth,RequestMetadata metadata){
        UUID actor=require(auth,"NOTIFICATION_SEND");String scope=actor+":NOTIFICATION_RETRY:"+id;Claim claim=claim(scope,key,protector.hash(id+"|retry"));
        if(!claim.claimed())return detail(claim.resourceId(),auth);
        StoredMessageDetail before=repository.detail(id).orElseThrow(()->new NotificationException("NOTIFICATION_NOT_FOUND"));
        if(!"FAILED".equals(before.message().status())||before.attemptCount()>=5||before.lastErrorCode()!=null&&before.lastErrorCode().startsWith("PERMANENT_"))throw new NotificationException("NOTIFICATION_NOT_RETRYABLE");
        if(!repository.retry(id))throw new NotificationException("NOTIFICATION_NOT_RETRYABLE");repository.complete(scope,key,id,200);audit(actor,metadata,"NOTIFICATION_RETRY_QUEUED","NOTIFICATION_MESSAGE",id,Map.of("attemptCount",before.attemptCount()));
        return detail(id,auth);
    }

    private DraftRequest normalize(DraftRequest request){
        if(request==null||!TYPES.contains(request.type())||!CHANNELS.contains(request.channel())||request.recipientFilter()==null||request.bodyTemplate()==null||request.scheduledAt()==null)throw new NotificationException("VALIDATION_ERROR");
        if(!"MANUAL".equals(request.channel())&&providers.stream().noneMatch(p->request.channel().equals(p.channel())))throw new NotificationException("NOTIFICATION_CHANNEL_NOT_CONFIGURED");
        OffsetDateTime now=OffsetDateTime.now(clock);OffsetDateTime scheduled=request.scheduledAt();if(scheduled.isBefore(now.minusSeconds(2)))throw new NotificationException("VALIDATION_ERROR");
        RecipientFilter filter=request.recipientFilter();List<UUID> studentIds=sorted(filter.studentIds()),groupIds=sorted(filter.classGroupIds());if(studentIds.size()>500||groupIds.size()>100)throw new NotificationException("NOTIFICATION_RECIPIENT_LIMIT");
        Map<String,String>inputVariables=request.variables()==null?Map.of():request.variables();if(inputVariables.size()>4||inputVariables.entrySet().stream().anyMatch(e->!Set.of("className","billingMonth","amount","studioName").contains(e.getKey())||e.getValue()==null||e.getValue().length()>500))throw new NotificationException("NOTIFICATION_TEMPLATE_INVALID");Map<String,String>variables=Map.copyOf(inputVariables);
        return new DraftRequest(request.type(),request.channel(),new RecipientFilter(studentIds,groupIds),request.subjectTemplate(),request.bodyTemplate(),variables,scheduled,request.optionalNotice());
    }
    private Analysis analyze(DraftRequest draft,List<Candidate> candidates){
        if(candidates.size()>500)throw new NotificationException("NOTIFICATION_RECIPIENT_LIMIT");int missing=0,excluded=0,deduplicated=0;Set<String>seen=new HashSet<>();List<RenderedTarget>eligible=new ArrayList<>();StringBuilder snapshot=new StringBuilder();
        for(Candidate c:candidates){snapshot.append(c.studentId()).append(':').append(c.guardianId()).append(':').append(c.consentId()).append(':').append(c.phoneHash()).append(':').append(c.emailHash()).append(':').append(protector.hash(String.valueOf(c.studentName())+'|'+c.guardianName())).append(';');
            byte[] recipientCiphertext=switch(draft.channel()){case "EMAIL"->c.emailCiphertext();case "SMS","KAKAO"->c.phoneCiphertext();default->null;};String recipientHash=switch(draft.channel()){case "EMAIL"->c.emailHash();case "SMS","KAKAO"->c.phoneHash();default->null;};String recipientLast4=switch(draft.channel()){case "EMAIL"->last4(c.emailCiphertext());case "SMS","KAKAO"->c.phoneLast4();default->null;};
            if(c.guardianId()==null||!"MANUAL".equals(draft.channel())&&recipientCiphertext==null){missing++;continue;}if(draft.optionalNotice()&&c.consentId()==null){excluded++;continue;}
            Map<String,String>vars=new LinkedHashMap<>(draft.variables());vars.put("studentName",c.studentName());vars.put("guardianName",c.guardianName());
            String subject=draft.subjectTemplate()==null?null:renderer.render(draft.subjectTemplate(),vars);String body=renderer.render(draft.bodyTemplate(),vars);
            String recipientKey="MANUAL".equals(draft.channel())?String.valueOf(c.guardianId()):recipientHash;String targetKey=recipientKey+"|"+String.valueOf(subject)+"|"+body;if(!seen.add(targetKey)){deduplicated++;continue;}eligible.add(new RenderedTarget(c,subject,body,Map.copyOf(vars),recipientCiphertext,recipientHash,recipientLast4));
        }
        return new Analysis(List.copyOf(eligible),missing,excluded,deduplicated,protector.hash(snapshot.toString()));
    }
    private void verifyToken(String token,DraftRequest draft,String fingerprint){
        try{String[] parts=token.split("\\.",2);String payload=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);String[] fields=payload.split("\\|",3);if(fields.length!=3||!fields[0].equals(requestHash(draft))||!fields[1].equals(fingerprint)||Long.parseLong(fields[2])<OffsetDateTime.now(clock).toEpochSecond()||!MessageDigest.isEqual(protector.hash("notification-preview:v1:"+payload).getBytes(StandardCharsets.US_ASCII),parts[1].getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException();}
        catch(RuntimeException exception){throw new NotificationException("NOTIFICATION_PREVIEW_STALE");}
    }
    private String requestHash(DraftRequest draft){return protector.hash(write(draft));}
    private Claim claim(String scope,UUID key,String hash){try{return repository.claim(scope,key,hash);}catch(NotificationRepository.NotificationConflictException exception){throw new NotificationException(exception.code());}}
    private byte[] protect(String text){return protector.protect(text).ciphertext();}
    private byte[] protectJson(Object value){return protect(write(value));}
    private String reveal(byte[] value){return value==null?null:protector.reveal(value);}
    private String last4(byte[] ciphertext){if(ciphertext==null)return null;String raw=reveal(ciphertext);return raw.substring(Math.max(0,raw.length()-4));}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(JacksonException exception){throw new IllegalStateException("notification snapshot serialization failed",exception);}}
    private static List<UUID> sorted(List<UUID> values){if(values==null)return List.of();return values.stream().distinct().sorted().toList();}
    private static String mask(String value){return value==null?"보호자":value.length()<2?"•":value.substring(0,1)+"•".repeat(Math.min(value.length()-1,4));}
    private static String maskRendered(String value,Map<String,String>variables){if(value==null)return null;String result=value;for(String variable:variables.values())if(variable!=null&&!variable.isBlank())result=result.replace(variable,mask(variable));return result;}
    private void audit(UUID actor,RequestMetadata m,String action,String type,UUID id,Map<String,Object>details){audit.record(new Event(clock.instant(),m.requestId(),"MGT-NOTIFICATION-SEND","OPERATION","ADMIN",actor,null,action,type,id,"SUCCESS",null,m.ipAddress(),m.userAgent(),details));}
    private static UUID require(Authentication auth,String permission){if(auth==null||auth.getAuthorities().stream().noneMatch(a->permission.equals(a.getAuthority())))throw new NotificationException(permission+"_DENIED");try{return UUID.fromString(auth.getName());}catch(Exception e){throw new NotificationException(permission+"_DENIED");}}
    private record RenderedTarget(Candidate candidate,String subject,String body,Map<String,String>variables,byte[]recipientCiphertext,String recipientHash,String recipientLast4){}
    private record Analysis(List<RenderedTarget>eligible,int missing,int excluded,int deduplicated,String fingerprint){}
}
