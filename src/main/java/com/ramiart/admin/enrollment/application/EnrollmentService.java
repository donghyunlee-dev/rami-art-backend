package com.ramiart.admin.enrollment.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.enrollment.application.EnrollmentModels.*;
import com.ramiart.admin.enrollment.application.EnrollmentRepository.*;
import com.ramiart.admin.inquiry.application.InquiryDataProtector;
import com.ramiart.admin.student.application.StudentDataProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {
    private static final Set<String> STATES=Set.of("NEW","CONTACTED","TRIAL_SCHEDULED","TRIAL_COMPLETED","WAITLISTED","ENROLLED","LOST");
    private static final Set<String> CHANNELS=Set.of("PHONE","SMS","EMAIL","IN_PERSON");
    private static final Pattern EMAIL=Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private final EnrollmentRepository repository;
    private final StudentDataProtector protector;
    private final InquiryDataProtector inquiryProtector;
    private final AuditRecorder audit;
    private final Clock clock;
    public EnrollmentService(EnrollmentRepository repository,StudentDataProtector protector,
            InquiryDataProtector inquiryProtector,AuditRecorder audit,Clock clock){this.repository=repository;this.protector=protector;this.inquiryProtector=inquiryProtector;this.audit=audit;this.clock=clock;}

    @Transactional(readOnly=true)
    public CasePage list(String statuses,UUID course,Instant from,Instant to,int page,int size){
        List<String> stateList=statuses==null||statuses.isBlank()?List.of("NEW","CONTACTED","TRIAL_SCHEDULED","TRIAL_COMPLETED","WAITLISTED"):Arrays.stream(statuses.split(",")).map(String::trim).distinct().toList();
        Instant end=to==null?clock.instant().plusSeconds(1):to,start=from==null?end.minus(Duration.ofDays(365*3L)):from;
        if(stateList.isEmpty()||stateList.stream().anyMatch(s->!STATES.contains(s))||page<0||!Set.of(10,20,50).contains(size)||!start.isBefore(end))fail("VALIDATION_ERROR");
        CaseRecordsPage records=repository.findPage(stateList,course,start,end,page,size);
        List<CaseSummary> items=records.items().stream().map(c->new CaseSummary(c.id(),maskName(protector.reveal(c.nameCiphertext())),c.phoneLast4(),c.status(),c.courseId(),c.courseName(),c.groupId(),c.groupName(),nextAction(c.status()),c.assigneeId(),c.assigneeName(),c.updatedAt(),c.version())).toList();
        return new CasePage(items,page,size,records.total(),records.total()==0?0:(int)((records.total()+size-1)/size));
    }
    @Transactional(readOnly=true) public List<AssigneeOption> assignees(){return repository.findAssignees();}
    @Transactional(readOnly=true) public CaseDetail detail(UUID id){return detail(record(id,false));}

    @Transactional
    public CaseDetail create(CaseCreate raw,UUID actor,UUID key,RequestMetadata meta){
        if(raw==null)fail("VALIDATION_ERROR");String scope="ENROLLMENT_CASE_CREATE",requestHash=hash(raw);Claim claim=repository.claim(scope,key,requestHash);if(!claim.claimed())return detail(claim.resourceId());
        String name;String phone;UUID course=raw.desiredCourseId();
        if(raw.inquiryId()!=null){repository.findCaseByInquiry(raw.inquiryId()).ifPresent(id->{throw new EnrollmentException("ENROLLMENT_CASE_EXISTS",Map.of("caseId",id));});InquiryLead inquiry=repository.findInquiry(raw.inquiryId()).orElseThrow(()->new EnrollmentException("INQUIRY_NOT_FOUND"));name=inquiryProtector.reveal(inquiry.nameCiphertext());phone=inquiryProtector.reveal(inquiry.phoneCiphertext());if(course==null)course=inquiry.courseId();}
        else{name=name(raw.leadName());phone=phone(raw.phone());}
        if(name==null||name.length()>100)fail("VALIDATION_ERROR");phone=phone(phone);var n=protector.protect(name);var p=protector.protect(phone);UUID id=UUID.randomUUID();
        repository.insertCase(id,raw.inquiryId(),n.ciphertext(),p.ciphertext(),p.hash(),phone.substring(phone.length()-4),course,raw.desiredClassGroupId(),actor);
        repository.insertActivity(id,"CREATED",null,null,null,null,null,clock.instant(),actor);event(actor,meta,"ENROLLMENT_CASE_CREATED",id,Map.of("source",raw.inquiryId()==null?"MANUAL":"INQUIRY","phoneLast4",phone.substring(phone.length()-4)));
        repository.complete(scope,key,id,201);return detail(id);
    }
    @Transactional
    public CaseDetail contact(UUID id,ActivityWrite raw,UUID actor,UUID key,RequestMetadata meta){
        if(raw==null||!CHANNELS.contains(raw.channel())||text(raw.outcome(),1,50)==null)fail("VALIDATION_ERROR");return mutate(id,raw.caseVersion(),actor,key,"CONTACT",meta,c->{String next="NEW".equals(c.status())?"CONTACTED":c.status();ensureOpen(c);if(!next.equals(c.status())&&repository.updateState(id,c.version(),c.status(),next,null,null,null,null,null,null,actor)==0)conflict();repository.insertActivity(id,"CONTACT",next.equals(c.status())?null:c.status(),next.equals(c.status())?null:next,raw.channel(),raw.outcome().trim(),encryptOptional(raw.note(),2000),raw.occurredAt()==null?clock.instant():raw.occurredAt(),actor);return next;});
    }
    @Transactional
    public CaseDetail trial(UUID id,TrialWrite raw,UUID actor,UUID key,RequestMetadata meta){
        if(raw==null||!Set.of("SCHEDULE","COMPLETE").contains(raw.action()))fail("VALIDATION_ERROR");return mutate(id,raw.caseVersion(),actor,key,"TRIAL_"+raw.action(),meta,c->{String next;UUID groupId=c.groupId();if("SCHEDULE".equals(raw.action())){if(!Set.of("NEW","CONTACTED").contains(c.status())||raw.trialStartsAt()==null||raw.classGroupId()==null)invalid();repository.lockClass(raw.classGroupId(),LocalDate.now(clock)).orElseThrow(()->new EnrollmentException("CLASS_GROUP_NOT_FOUND"));groupId=raw.classGroupId();next="TRIAL_SCHEDULED";}else{if(!"TRIAL_SCHEDULED".equals(c.status()))invalid();next="TRIAL_COMPLETED";}if(repository.updateState(id,c.version(),c.status(),next,null,groupId,raw.trialStartsAt(),null,null,null,actor)==0)conflict();repository.insertActivity(id,"TRIAL",c.status(),next,null,null,encryptOptional(raw.note(),2000),clock.instant(),actor);return next;});
    }
    @Transactional
    public CaseDetail waitlist(UUID id,WaitlistWrite raw,UUID actor,UUID key,RequestMetadata meta){
        if(raw==null||raw.classGroupId()==null)fail("VALIDATION_ERROR");return mutate(id,raw.caseVersion(),actor,key,"WAITLIST",meta,c->{ensureOpen(c);if("WAITLISTED".equals(c.status()))invalid();repository.lockClass(raw.classGroupId(),LocalDate.now(clock)).orElseThrow(()->new EnrollmentException("CLASS_GROUP_NOT_FOUND"));if(repository.updateState(id,c.version(),c.status(),"WAITLISTED",null,raw.classGroupId(),null,clock.instant(),null,null,actor)==0)conflict();repository.insertActivity(id,"WAITLIST",c.status(),"WAITLISTED",null,null,encryptOptional(raw.note(),2000),clock.instant(),actor);return "WAITLISTED";});
    }
    @Transactional
    public CaseDetail lost(UUID id,LostWrite raw,UUID actor,UUID key,RequestMetadata meta){
        String reason=raw==null?null:text(raw.reason(),5,200);if(reason==null)fail("VALIDATION_ERROR");return mutate(id,raw.caseVersion(),actor,key,"LOST",meta,c->{ensureOpen(c);if(repository.updateState(id,c.version(),c.status(),"LOST",null,null,null,null,reason,null,actor)==0)conflict();repository.insertActivity(id,"STATUS",c.status(),"LOST",null,null,null,clock.instant(),actor);return "LOST";});
    }

    @Transactional
    public CaseDetail assign(UUID id,AssigneeWrite raw,UUID actor,UUID key,RequestMetadata meta){
        if(raw==null||raw.assigneeId()==null||raw.caseVersion()<0)fail("VALIDATION_ERROR");
        String scope="ENROLLMENT_ASSIGN:"+id,requestHash=hash(raw);Claim claim=repository.claim(scope,key,requestHash);
        if(!claim.claimed())return detail(claim.resourceId());
        CaseRecord c=record(id,true);if(c.version()!=raw.caseVersion())conflict();ensureOpen(c);
        if(!repository.isActiveAssignee(raw.assigneeId()))throw new EnrollmentException("ENROLLMENT_ASSIGNEE_INVALID");
        if(!c.assigneeId().equals(raw.assigneeId())){
            if(repository.updateAssignee(id,c.version(),raw.assigneeId(),actor)==0)conflict();
            event(actor,meta,"ENROLLMENT_ASSIGNEE_CHANGED",id,Map.of("fromAssigneeId",c.assigneeId(),"toAssigneeId",raw.assigneeId()));
        }
        repository.complete(scope,key,id,200);return detail(id);
    }

    @Transactional
    public EnrollmentPreview preview(UUID id,EnrollWrite raw){CaseRecord c=record(id,true);validateEnroll(c,raw);ClassLock group=lockGroup(raw);List<UUID> required=repository.requiredConsentIds();List<UUID> missing=required.stream().filter(v->!raw.consentIds().contains(v)).toList();List<StoredGuardian> guardians=guardians(raw.guardians());List<DuplicateCandidate> duplicates=maskedDuplicates(repository.duplicateCandidates(search(raw.student().name()),raw.student().birthday(),guardians.stream().map(StoredGuardian::phoneHash).toList()));boolean override=duplicates.isEmpty()||text(raw.duplicateOverrideReason(),5,200)!=null;boolean can=group.occupancy()<group.capacity()&&missing.isEmpty()&&override;return new EnrollmentPreview(token(id,raw,group,required,duplicates),group.capacity(),group.occupancy(),Math.max(0,group.capacity()-group.occupancy()),required,missing,duplicates,can);}

    @Transactional
    public EnrollmentResult enroll(UUID id,EnrollWrite raw,UUID actor,UUID key,RequestMetadata meta){
        String scope="ENROLLMENT_ENROLL:"+id;Claim claim=repository.claim(scope,key,hash(fingerprint(raw)));if(!claim.claimed()){CaseDetail detail=detail(claim.resourceId());return result(detail);}
        CaseRecord c=record(id,true);validateEnroll(c,raw);ClassLock group=lockGroup(raw);if(group.occupancy()>=group.capacity())throw new EnrollmentException("ENROLLMENT_CAPACITY_FULL");List<UUID> activeSlots=repository.activeSlotIds(raw.classGroupId(),raw.scheduleSlotIds());if(activeSlots.size()!=raw.scheduleSlotIds().stream().distinct().count())fail("ENROLLMENT_SLOT_INVALID");List<UUID> required=repository.requiredConsentIds();List<UUID> missing=required.stream().filter(v->!raw.consentIds().contains(v)).toList();if(!missing.isEmpty())throw new EnrollmentException("ENROLLMENT_CONSENT_REQUIRED",Map.of("missingConsentIds",missing));
        List<StoredGuardian> guardians=guardians(raw.guardians());List<DuplicateCandidate> duplicates=maskedDuplicates(repository.duplicateCandidates(search(raw.student().name()),raw.student().birthday(),guardians.stream().map(StoredGuardian::phoneHash).toList()));if(!duplicates.isEmpty()&&text(raw.duplicateOverrideReason(),5,200)==null)throw new EnrollmentException("ENROLLMENT_DUPLICATE_CONFIRMATION_REQUIRED",Map.of("candidates",duplicates));verifyToken(id,raw,group,required,duplicates);
        StudentWrite normalizedStudent=normalize(raw.student());UUID student=UUID.randomUUID();repository.insertStudent(student,normalizedStudent,search(normalizedStudent.name()),actor);guardians.forEach(g->repository.insertGuardian(student,g));repository.insertInitialStatus(student,normalizedStudent.joinedAt(),actor);List<UUID> assignments=activeSlots.stream().map(slot->repository.insertAssignment(student,slot,raw.effectiveFrom(),actor)).toList();UUID primary=guardians.stream().filter(g->g.value().primaryContact()).findFirst().orElseThrow().id();required.forEach(policy->repository.insertConsent(student,primary,policy,actor));
        if(repository.updateState(id,c.version(),c.status(),"ENROLLED",group.courseId(),group.id(),null,null,null,student,actor)==0)conflict();repository.insertActivity(id,"ENROLLED",c.status(),"ENROLLED",null,null,null,clock.instant(),actor);event(actor,meta,"ENROLLMENT_COMPLETED",id,Map.of("studentId",student,"guardianCount",guardians.size(),"assignmentCount",assignments.size(),"duplicateOverride",!duplicates.isEmpty()));repository.complete(scope,key,id,201);return new EnrollmentResult(detail(id),student,guardians.stream().map(StoredGuardian::id).toList(),assignments);
    }

    private CaseDetail mutate(UUID id,long version,UUID actor,UUID key,String operation,RequestMetadata meta,java.util.function.Function<CaseRecord,String> action){String scope="ENROLLMENT_"+operation+":"+id;Claim claim=repository.claim(scope,key,hash(operation+"|"+version));if(!claim.claimed())return detail(claim.resourceId());CaseRecord c=record(id,true);if(c.version()!=version)conflict();String next=action.apply(c);event(actor,meta,"ENROLLMENT_"+operation,id,Map.of("fromStatus",c.status(),"toStatus",next));repository.complete(scope,key,id,200);return detail(id);}
    private void validateEnroll(CaseRecord c,EnrollWrite raw){ensureOpen(c);if(raw==null||raw.caseVersion()!=c.version()||raw.student()==null||raw.classGroupId()==null||raw.scheduleSlotIds()==null||raw.scheduleSlotIds().isEmpty()||raw.consentIds()==null||raw.effectiveFrom()==null) {if(raw!=null&&raw.caseVersion()!=c.version())conflict();fail("VALIDATION_ERROR");}normalize(raw.student());guardians(raw.guardians());}
    private ClassLock lockGroup(EnrollWrite raw){ClassLock g=repository.lockClass(raw.classGroupId(),raw.effectiveFrom()).orElseThrow(()->new EnrollmentException("CLASS_GROUP_NOT_FOUND"));if(!"ACTIVE".equals(g.status())||raw.effectiveFrom().isBefore(g.startsOn())||g.endsOn()!=null&&raw.effectiveFrom().isAfter(g.endsOn()))fail("ENROLLMENT_SLOT_INVALID");return g;}
    private List<StoredGuardian> guardians(List<GuardianWrite> values){if(values==null||values.isEmpty()||values.size()>5||values.stream().filter(GuardianWrite::primaryContact).count()!=1)fail("VALIDATION_ERROR");Set<String> phones=new HashSet<>();Set<Integer> orders=new HashSet<>();List<StoredGuardian> out=new ArrayList<>();for(GuardianWrite g:values){String name=name(g.name()),phone=phone(g.phone()),relationship=g.relationship(),detail=text(g.relationshipDetail(),1,50),channel=g.preferredChannel()==null?"SMS":g.preferredChannel(),email=text(g.email(),1,254);if(name==null||name.length()>100||!Set.of("MOTHER","FATHER","GRANDPARENT","GUARDIAN","OTHER").contains(relationship)||("OTHER".equals(relationship))!=(detail!=null)||!Set.of("SMS","KAKAO","EMAIL","MANUAL").contains(channel)||"EMAIL".equals(channel)&&email==null||email!=null&&!EMAIL.matcher(email).matches()||g.displayOrder()<0||!orders.add(g.displayOrder()))fail("VALIDATION_ERROR");var p=protector.protect(phone);if(!phones.add(p.hash()))fail("GUARDIAN_PHONE_DUPLICATED");var e=email==null?null:protector.protect(email.toLowerCase(Locale.ROOT));out.add(new StoredGuardian(UUID.randomUUID(),new GuardianWrite(name,relationship,detail,phone,email,channel,g.primaryContact(),g.displayOrder()),p.ciphertext(),p.hash(),phone.substring(phone.length()-4),e==null?null:e.ciphertext(),e==null?null:e.hash(),email==null?null:email.substring(email.indexOf('@')+1).toLowerCase(Locale.ROOT)));}for(int i=0;i<out.size();i++)if(!orders.contains(i))fail("VALIDATION_ERROR");return out;}
    private StudentWrite normalize(StudentWrite s){if(s==null)fail("VALIDATION_ERROR");String name=name(s.name()),school=text(s.schoolName(),1,150);LocalDate today=LocalDate.now(clock);if(name==null||name.length()>100||s.joinedAt()==null||s.joinedAt().isAfter(today.plusDays(31))||s.birthday()!=null&&(s.birthday().isAfter(today)||s.joinedAt().isBefore(s.birthday())))fail("VALIDATION_ERROR");return new StudentWrite(name,s.birthday(),school,s.joinedAt());}
    private CaseRecord record(UUID id,boolean lock){return repository.findCase(id,lock).orElseThrow(()->new EnrollmentException("ENROLLMENT_NOT_FOUND"));}
    private CaseDetail detail(CaseRecord c){List<ActivityView> activities=repository.findActivities(c.id()).stream().map(a->new ActivityView(a.id(),a.sequence(),a.type(),a.fromStatus(),a.toStatus(),a.channel(),a.outcome(),a.noteCiphertext()==null?null:protector.reveal(a.noteCiphertext()),a.occurredAt(),a.createdBy())).toList();return new CaseDetail(c.id(),c.inquiryId(),protector.reveal(c.nameCiphertext()),protector.reveal(c.phoneCiphertext()),c.phoneLast4(),c.status(),c.courseId(),c.courseName(),c.groupId(),c.groupName(),c.trialAt(),c.waitlistedAt(),"WAITLISTED".equals(c.status())?repository.waitlistPosition(c.id(),c.groupId(),c.waitlistedAt()):null,c.studentId(),c.lostReason(),c.version(),c.capacity(),c.occupancy(),activities,actions(c.status()),nextAction(c.status()),c.assigneeId(),c.assigneeName());}
    private EnrollmentResult result(CaseDetail d){return new EnrollmentResult(d,d.studentId(),repository.guardianIds(d.studentId()),repository.assignmentIds(d.studentId()));}
    private byte[] encryptOptional(String value,int max){String v=text(value,1,max);return v==null?null:protector.protect(v).ciphertext();}
    private String token(UUID id,EnrollWrite r,ClassLock g,List<UUID> consents,List<DuplicateCandidate> duplicates){String payload=id+"|"+clock.instant().getEpochSecond()+"|"+fingerprint(r)+"|"+g.occupancy()+"|"+hash(consents)+"|"+hash(duplicates);return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"."+protector.hash(payload);}
    private void verifyToken(UUID id,EnrollWrite r,ClassLock g,List<UUID> consents,List<DuplicateCandidate> duplicates){try{String[] token=r.previewToken().split("\\.");String payload=new String(Base64.getUrlDecoder().decode(token[0]),StandardCharsets.UTF_8);String[] values=payload.split("\\|",-1);if(token.length!=2||values.length!=6||!MessageDigest.isEqual(token[1].getBytes(),protector.hash(payload).getBytes())||!values[0].equals(id.toString())||clock.instant().getEpochSecond()-Long.parseLong(values[1])>300||!values[2].equals(fingerprint(r))||!values[3].equals(Integer.toString(g.occupancy()))||!values[4].equals(hash(consents))||!values[5].equals(hash(duplicates)))fail("ENROLLMENT_PREVIEW_REQUIRED");}catch(Exception e){if(e instanceof EnrollmentException ee)throw ee;fail("ENROLLMENT_PREVIEW_REQUIRED");}}
    private String fingerprint(EnrollWrite r){return hash(r.caseVersion()+"|"+r.student()+"|"+r.guardians()+"|"+r.classGroupId()+"|"+r.scheduleSlotIds()+"|"+r.effectiveFrom()+"|"+r.consentIds()+"|"+text(r.duplicateOverrideReason(),5,200));}
    private void ensureOpen(CaseRecord c){if(Set.of("ENROLLED","LOST").contains(c.status()))invalid();}
    private static List<String> actions(String status){return switch(status){case "ENROLLED","LOST"->List.of();case "WAITLISTED"->List.of("CONTACT","ENROLL","LOST");case "TRIAL_SCHEDULED"->List.of("CONTACT","COMPLETE_TRIAL","WAITLIST","LOST");default->List.of("CONTACT","SCHEDULE_TRIAL","WAITLIST","ENROLL","LOST");};}
    private static String nextAction(String status){return switch(status){case "NEW"->"CONTACT";case "CONTACTED"->"SCHEDULE_TRIAL";case "TRIAL_SCHEDULED"->"COMPLETE_TRIAL";case "TRIAL_COMPLETED","WAITLISTED"->"ENROLL";default->null;};}
    private static String name(String value){String v=text(value,1,100);return v==null?null:v.replaceAll("\\s+"," ");}private static String search(String value){return name(value).toLowerCase(Locale.ROOT).replaceAll("\\s+","");}
    private static String maskName(String value){if(value==null||value.isBlank())return "*";int[] points=value.codePoints().toArray();if(points.length==1)return "*";return new String(points,0,1)+"*".repeat(points.length-1);}
    private static List<DuplicateCandidate> maskedDuplicates(List<DuplicateCandidate> values){return values.stream().map(v->new DuplicateCandidate(v.id(),maskName(v.studentName()),v.status(),v.matchedBy())).toList();}
    private static String phone(String value){if(value==null)fail("VALIDATION_ERROR");String v=value.replaceAll("[^0-9+]","");if(v.startsWith("010"))v="+82"+v.substring(1);else if(v.startsWith("0"))v="+82"+v.substring(1);if(!v.matches("\\+[1-9][0-9]{7,14}"))fail("VALIDATION_ERROR");return v;}
    private static String text(String value,int min,int max){if(value==null)return null;String v=value.trim();return v.length()<min||v.length()>max?null:v;}
    private static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void conflict(){throw new EnrollmentException("ENROLLMENT_VERSION_CONFLICT");}private static void invalid(){throw new EnrollmentException("ENROLLMENT_INVALID_TRANSITION");}private static void fail(String code){throw new EnrollmentException(code);}
    private void event(UUID actor,RequestMetadata m,String action,UUID target,Map<String,Object> details){audit.record(new Event(clock.instant(),m.requestId(),"MGT-ENROLLMENT-PIPELINE","OPERATION","ADMIN",actor,null,action,"ENROLLMENT_CASE",target,"SUCCESS",null,m.ip(),m.userAgent(),details));}
    public record RequestMetadata(String requestId,String ip,String userAgent){}
}
