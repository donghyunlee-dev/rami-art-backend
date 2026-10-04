package com.ramiart.admin.tuition.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.tuition.application.TuitionPolicyModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class TuitionPolicyService {
    public record Metadata(String requestId,String ip,String userAgent) {}
    private final TuitionPolicyRepository repository; private final AuditRecorder audit; private final Clock clock;
    public TuitionPolicyService(TuitionPolicyRepository repository,AuditRecorder audit,Clock clock){this.repository=repository;this.audit=audit;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Policy get(int year,String mode){checkYear(year); if(!mode.equals("DRAFT")&&!mode.equals("PUBLISHED"))throw new TuitionPolicyException("VALIDATION_ERROR");
        Policy selected=repository.byYearStatus(year,mode).orElse(null);
        if(selected!=null)return selected;
        Policy published=repository.byYearStatus(year,"PUBLISHED").orElse(null);
        if(mode.equals("DRAFT")&&published!=null)return new Policy(published.id(),null,year,published.revision(),"PUBLISHED","PUBLISHED",false,published.version(),published.defaultDueDay(),published.id(),published.publishedAt(),published.publishedBy(),published.items(),published.validation(),new Actions(true,false,false,false));
        if(published==null)throw new TuitionPolicyException("TUITION_POLICY_NOT_FOUND");
        return published;
    }
    @Transactional public Policy create(int year,UUID actor,UUID key,Metadata meta){checkYear(year);String scope=actor+":TUITION_POLICY_DRAFT_CREATE:"+year;var claim=repository.claim(scope,key,digest("POST:"+year));
        if(!claim.claimed())return repository.byId(claim.resourceId()).orElseThrow();if(repository.byYearStatus(year,"DRAFT").isPresent())throw new TuitionPolicyException("TUITION_POLICY_DRAFT_EXISTS");
        Policy base=repository.byYearStatus(year,"PUBLISHED").orElse(null);int revision=base==null?1:base.revision()+1;UUID id=repository.create(year,revision,base==null?null:base.id(),base==null?25:base.defaultDueDay(),actor);
        record(actor,meta,"TUITION_POLICY_DRAFT_CREATED",id,Map.of("revision",revision));repository.complete(scope,key,id,201);return repository.byId(id).orElseThrow();}
    @Transactional public Policy save(int year,UUID id,Write write,UUID actor,UUID key,Metadata meta){checkYear(year);validate(write);String scope=actor+":TUITION_POLICY_DRAFT_SAVE:"+id;var claim=repository.claim(scope,key,digest("PUT:"+id+":"+write));if(!claim.claimed())return repository.byId(id).orElseThrow();Policy current=repository.byId(id).filter(p->p.year()==year&&p.status().equals("DRAFT")).orElseThrow(()->new TuitionPolicyException("TUITION_POLICY_DRAFT_NOT_FOUND"));
        if(write.version()!=current.version())throw new TuitionPolicyException("TUITION_POLICY_VERSION_CONFLICT");
        if(repository.update(id,write.version(),write.defaultDueDay(),write.items())!=1)throw new TuitionPolicyException("TUITION_POLICY_VERSION_CONFLICT");
        record(actor,meta,"TUITION_POLICY_DRAFT_SAVED",id,Map.of("revision",current.revision()));repository.complete(scope,key,id,200);return repository.byId(id).orElseThrow();}
    @Transactional public Policy publish(int year,UUID draftId,long version,UUID actor,UUID key,Metadata meta){checkYear(year);String scope=actor+":TUITION_POLICY_PUBLISH:"+draftId;var claim=repository.claim(scope,key,digest("POST:"+draftId+":"+version));if(!claim.claimed())return repository.byId(claim.resourceId()).orElseThrow();Policy draft=repository.byId(draftId).filter(p->p.year()==year&&p.status().equals("DRAFT")).orElseThrow(()->new TuitionPolicyException("TUITION_POLICY_DRAFT_NOT_FOUND"));
        if(draft.version()!=version)throw new TuitionPolicyException("TUITION_POLICY_VERSION_CONFLICT");if(!draft.validation().publishable())throw new TuitionPolicyException("TUITION_POLICY_NOT_PUBLISHABLE");
        repository.publish(draftId,version,actor);record(actor,meta,"TUITION_POLICY_PUBLISHED",draftId,Map.of("revision",draft.revision()));repository.complete(scope,key,draftId,201);return repository.byId(draftId).orElseThrow();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public AssignmentOptions options(int year){checkYear(year);Policy p=repository.byYearStatus(year,"PUBLISHED").orElseThrow(()->new TuitionPolicyException("TUITION_POLICY_NOT_FOUND"));return new AssignmentOptions(year,p.items());}
    private void record(UUID actor,Metadata m,String action,UUID id,Map<String,Object> data){audit.record(new Event(clock.instant(),m.requestId(),"MGT-TUITION-POLICY","OPERATION","ADMIN",actor,null,action,"TUITION_POLICY",id,"SUCCESS",null,m.ip(),m.userAgent(),data));}
    private void checkYear(int year){int now=Year.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getValue();if(year<now-5||year>now+2)throw new TuitionPolicyException("TUITION_POLICY_YEAR_INVALID");}
    private static void validate(Write w){if(w==null||w.defaultDueDay()<1||w.defaultDueDay()>31||w.items()==null)throw new TuitionPolicyException("VALIDATION_ERROR");Set<UUID> ids=new HashSet<>();Set<Integer> counts=new HashSet<>();for(Item i:w.items())if(i==null||i.id()==null||i.lessonCountPerWeek()<1||i.lessonCountPerWeek()>7||i.monthlyAmount()<0||i.monthlyAmount()>999_999_999_999L||!ids.add(i.id()))throw new TuitionPolicyException("VALIDATION_ERROR");else if(!counts.add(i.lessonCountPerWeek()))throw new TuitionPolicyException("TUITION_POLICY_DUPLICATE_COUNT");}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
