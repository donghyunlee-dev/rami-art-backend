package com.ramiart.admin.student.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.student.application.StudentModels.*;
import com.ramiart.admin.student.application.StudentRepository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentService {
    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private static final Set<String> STATUSES=Set.of("ACTIVE","PAUSED","GRADUATED","DROPPED");
    private static final Pattern EMAIL=Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SENSITIVE_REASON=Pattern.compile("(?i)(\\d{6}[- ]?[1-4]\\d{6}|01[016789][- ]?\\d{3,4}[- ]?\\d{4})");
    private final StudentRepository repository;
    private final StudentDataProtector protector;
    private final AuditRecorder audit;
    private final Clock clock;
    public StudentService(StudentRepository repository,StudentDataProtector protector,AuditRecorder audit,Clock clock){this.repository=repository;this.protector=protector;this.audit=audit;this.clock=clock;}

    @Transactional(readOnly=true)
    public StudentPage list(String keyword,String statuses,LocalDate from,LocalDate to,String birthdayFrom,String birthdayTo,int page,int size,String sort){
        String k=trim(keyword); if(k!=null&&(k.length()<2||k.length()>50)) fail("INVALID_STUDENT_QUERY");
        List<String> ss=statuses==null||statuses.isBlank()?List.of("ACTIVE","PAUSED"):Arrays.stream(statuses.split(",")).map(String::trim).distinct().toList();
        if(ss.isEmpty()||ss.stream().anyMatch(s->!STATUSES.contains(s))||page<0||!Set.of(10,20,50).contains(size)||!Set.of("studentName,asc","studentName,desc","joinedAt,asc","joinedAt,desc","updatedAt,asc","updatedAt,desc").contains(sort)) fail("INVALID_STUDENT_QUERY");
        if(from!=null&&to!=null&&(from.isAfter(to)||from.plusYears(5).isBefore(to))) fail("INVALID_STUDENT_QUERY");
        if((birthdayFrom==null)!=(birthdayTo==null)||(birthdayFrom!=null&&(!mmdd(birthdayFrom)||!mmdd(birthdayTo)))) fail("INVALID_STUDENT_QUERY");
        return repository.findStudents(k,ss,from,to,birthdayFrom,birthdayTo,page,size,sort);
    }
    @Transactional(readOnly=true) public StudentDetail detail(UUID id){return toDetail(record(id));}
    @Transactional(readOnly=true)
    public List<StudentSummary> duplicateCandidates(StudentCreate raw) {
        StudentCreate command=normalize(raw);
        validate(command.studentName(),command.birthday(),command.schoolName(),command.joinedAt(),command.guardians());
        List<StoredGuardian> guardians=protect(command.guardians(),null);
        return repository.findDuplicateCandidates(search(command.studentName()),command.birthday(),
                guardians.stream().map(StoredGuardian::phoneHash).toList());
    }
    @Transactional
    public StudentDetail create(StudentCreate raw,UUID actor,UUID key,RequestMetadata meta){
        StudentCreate c=normalize(raw); validate(c.studentName(),c.birthday(),c.schoolName(),c.joinedAt(),c.guardians());
        String scope="STUDENT_CREATE",hash=hash(c); Claim claim=repository.claim(scope,key,hash); if(!claim.claimed())return detail(claim.resourceId());
        List<StoredGuardian> stored=protect(c.guardians(),null);
        List<StudentSummary> duplicates=repository.findDuplicateCandidates(search(c.studentName()),c.birthday(),stored.stream().map(StoredGuardian::phoneHash).toList());
        if(!duplicates.isEmpty()&&!c.duplicateConfirmed()) throw new StudentException("STUDENT_DUPLICATE_CANDIDATE",Map.of("candidates",duplicates.stream().map(candidate->Map.of(
                "id",candidate.id(),"studentName",candidate.studentName(),"birthdayMonthDay",Objects.toString(candidate.birthdayMonthDay(),""),
                "status",candidate.status(),"matchedBy",List.of("NAME_BIRTHDAY_OR_GUARDIAN_PHONE"))).toList()));
        UUID id=UUID.randomUUID(); repository.insertStudent(id,c,search(c.studentName()),actor);
        stored.forEach(g->repository.insertGuardian(id,g.value(),g.id(),g.phoneCiphertext(),g.phoneHash(),g.phoneLast4(),g.emailCiphertext(),g.emailHash(),g.emailDomain()));
        repository.insertStatusHistory(id,null,"ACTIVE",c.joinedAt(),"신규 등록",actor,0);
        event(actor,meta,"MGT-STUDENT-CREATE","STUDENT_CREATED",id,Map.of("guardianCount",stored.size())); repository.complete(scope,key,id,201); return detail(id);
    }
    @Transactional
    public StudentDetail update(UUID id,StudentUpdate raw,UUID actor,UUID key,RequestMetadata meta){
        StudentUpdate c=normalize(raw);validate(c.studentName(),c.birthday(),c.schoolName(),c.joinedAt(),c.guardians());
        String scope="STUDENT_UPDATE:"+id;Claim claim=repository.claim(scope,key,hash(c));if(!claim.claimed())return detail(claim.resourceId());
        StudentRecord current=record(id);List<GuardianRecord> existing=repository.findGuardians(id);Set<UUID> owned=existing.stream().map(GuardianRecord::id).collect(java.util.stream.Collectors.toSet());
        if(c.guardians().stream().map(GuardianWrite::id).filter(Objects::nonNull).anyMatch(gid->!owned.contains(gid)))fail("VALIDATION_ERROR");
        Set<UUID> requested=c.guardians().stream().map(GuardianWrite::id).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if(existing.stream().map(GuardianRecord::id).filter(gid->!requested.contains(gid)).anyMatch(repository::guardianReferenced))throw new StudentException("GUARDIAN_REFERENCED");
        List<StoredGuardian> stored=protect(c.guardians(),id);
        if(repository.updateStudent(id,c,search(c.studentName()),actor)==0)fail("STUDENT_VERSION_CONFLICT");
        repository.replaceGuardians(id,stored);event(actor,meta,"MGT-STUDENT-EDIT","STUDENT_UPDATED",id,Map.of("previousVersion",c.version(),"guardianCount",stored.size()));repository.complete(scope,key,id,200);return detail(id);
    }
    @Transactional(readOnly=true)
    public StatusPreview preview(UUID id,StatusWrite c){StudentRecord current=record(id);validateStatus(current,c);Impacts impacts=repository.impacts(id);String token=token(id,c,impacts,clock.instant());return new StatusPreview(token,current.status(),c.toStatus(),c.effectiveDate(),impacts);}
    @Transactional
    public StatusChange changeStatus(UUID id,StatusWrite c,UUID actor,UUID key,RequestMetadata meta){
        String scope="STUDENT_STATUS:"+id;Claim claim=repository.claim(scope,key,hash(c));if(!claim.claimed()){StatusChangeRecord saved=repository.findStatusChange(claim.resourceId()).orElseThrow(()->new StudentException("STUDENT_NOT_FOUND"));return new StatusChange(saved.id(),saved.fromStatus(),saved.toStatus(),saved.effectiveDate(),saved.changedAt(),saved.version(),repository.impacts(id));}
        StudentRecord current=record(id);validateStatus(current,c);Impacts impacts=repository.impacts(id);verifyToken(id,c,impacts);
        if(repository.updateStatus(id,c.version(),c.toStatus(),actor)==0)fail("STUDENT_VERSION_CONFLICT");
        UUID history=repository.insertStatusHistory(id,current.status(),c.toStatus(),c.effectiveDate(),c.reason().trim(),actor,c.version()+1);
        event(actor,meta,"MGT-STUDENT-STATUS","STUDENT_STATUS_CHANGED",id,Map.of("fromStatus",current.status(),"toStatus",c.toStatus(),"reasonLength",c.reason().trim().length(),"previousVersion",c.version()));repository.complete(scope,key,history,201);
        return new StatusChange(history,current.status(),c.toStatus(),c.effectiveDate(),clock.instant(),c.version()+1,impacts);
    }
    @Transactional(readOnly=true)
    public NotePage notes(UUID student,String cursor,int size,UUID actor){if(size<5||size>20)fail("STUDENT_NOTE_CURSOR_INVALID");if(!repository.studentExists(student))fail("STUDENT_NOT_FOUND");Cursor c=decodeCursor(cursor);List<NoteRecord> rows=repository.findNotes(student,c.at(),c.id(),size+1);boolean more=rows.size()>size;List<NoteView> items=rows.stream().limit(size).map(n->note(n,actor)).toList();String next=more?encodeCursor(rows.get(size-1)):null;return new NotePage(items,new NotePageInfo(size,next));}
    @Transactional
    public NoteView createNote(UUID student,NoteCreate raw,UUID actor,UUID key,RequestMetadata meta){String content=content(raw==null?null:raw.content());if(!repository.studentExists(student))fail("STUDENT_NOT_FOUND");String scope="STUDENT_NOTE_CREATE:"+student;Claim claim=repository.claim(scope,key,hash(content));if(!claim.claimed())return note(repository.findNote(claim.resourceId()).orElseThrow(()->new StudentException("STUDENT_NOTE_NOT_FOUND")),actor);UUID id=UUID.randomUUID();repository.insertNote(id,student,protector.protect(content).ciphertext(),actor);event(actor,meta,"MGT-STUDENT-NOTE","STUDENT_NOTE_CREATED",id,Map.of("contentLength",content.length()));repository.complete(scope,key,id,201);return note(repository.findNote(id).orElseThrow(),actor);}
    @Transactional
    public NoteView updateNote(UUID id,NoteUpdate raw,UUID actor,RequestMetadata meta){String content=content(raw==null?null:raw.content());NoteRecord before=repository.findNote(id).orElseThrow(()->new StudentException("STUDENT_NOTE_NOT_FOUND"));if(!before.createdBy().equals(actor))fail("STUDENT_NOTE_AUTHOR_REQUIRED");if(!clock.instant().isBefore(before.createdAt().plus(Duration.ofHours(24))))fail("STUDENT_NOTE_EDIT_WINDOW_EXPIRED");if(repository.updateNote(id,raw.version(),protector.protect(content).ciphertext(),actor)==0)fail("STUDENT_NOTE_VERSION_CONFLICT");event(actor,meta,"MGT-STUDENT-NOTE","STUDENT_NOTE_UPDATED",id,Map.of("contentLength",content.length(),"previousVersion",raw.version()));return note(repository.findNote(id).orElseThrow(),actor);}
    @Transactional
    public void hideNote(UUID id,NoteHide raw,UUID actor,RequestMetadata meta){String reason=trim(raw==null?null:raw.reason());if(reason==null||reason.length()<5||reason.length()>200)fail("VALIDATION_ERROR");if(repository.findNote(id).isEmpty())fail("STUDENT_NOTE_NOT_FOUND");if(repository.hideNote(id,raw.version(),reason,actor)==0)fail("STUDENT_NOTE_VERSION_CONFLICT");event(actor,meta,"MGT-STUDENT-NOTE","STUDENT_NOTE_HIDDEN",id,Map.of("reasonLength",reason.length(),"previousVersion",raw.version()));}

    private StudentDetail toDetail(StudentRecord s){List<GuardianView> guardians=repository.findGuardians(s.id()).stream().map(g->{String phone=protector.reveal(g.phoneCiphertext());String email=g.emailCiphertext()==null?null:protector.reveal(g.emailCiphertext());return new GuardianView(g.id(),g.name(),g.relationship(),g.relationshipDetail(),phone,"***-****-"+phone.substring(phone.length()-4),email,maskEmail(email),g.preferredChannel(),!("EMAIL".equals(g.preferredChannel())&&email==null),g.primaryContact(),g.displayOrder());}).toList();return new StudentDetail(s.id(),s.name(),s.school(),s.birthday(),lessonCount(s.id()),s.status(),s.joinedAt(),s.version(),guardians,new StatusSummary(s.lastChangedAt(),s.lastReason()),actions(s.status()));}
    private int lessonCount(UUID id){return repository.lessonCount(id);}
    private StudentRecord record(UUID id){return repository.findStudent(id).orElseThrow(()->new StudentException("STUDENT_NOT_FOUND"));}
    private List<StoredGuardian> protect(List<GuardianWrite> values,UUID student){Set<UUID> ids=new HashSet<>();Set<String> phones=new HashSet<>();List<StoredGuardian> out=new ArrayList<>();for(GuardianWrite g:values){if(g.id()!=null&&!ids.add(g.id()))fail("VALIDATION_ERROR");String phone=phone(g.phone());var p=protector.protect(phone);if(!phones.add(p.hash()))fail("GUARDIAN_PHONE_DUPLICATED");String email=trim(g.email());if(email!=null)email=email.toLowerCase(Locale.ROOT);var e=email==null?null:protector.protect(email);out.add(new StoredGuardian(g,g.id()==null?UUID.randomUUID():g.id(),p.ciphertext(),p.hash(),phone.substring(phone.length()-4),e==null?null:e.ciphertext(),e==null?null:e.hash(),email==null?null:email.substring(email.indexOf('@')+1)));}return out;}
    private void validate(String name,LocalDate birthday,String school,LocalDate joined,List<GuardianWrite> guardians){LocalDate today=LocalDate.now(clock.withZone(STUDIO_ZONE));if(name==null||name.length()>100||joined==null||joined.isAfter(today.plusDays(31))||birthday!=null&&(birthday.isAfter(today)||joined.isBefore(birthday))||school!=null&&school.length()>150)fail("VALIDATION_ERROR");if(guardians==null||guardians.isEmpty())fail("GUARDIAN_REQUIRED");if(guardians.size()>5)fail("VALIDATION_ERROR");if(guardians.stream().filter(GuardianWrite::primaryContact).count()!=1)fail("PRIMARY_GUARDIAN_REQUIRED");Set<Integer> orders=new HashSet<>();for(GuardianWrite g:guardians){if(trim(g.name())==null||g.name().trim().length()>100||!Set.of("MOTHER","FATHER","GRANDPARENT","GUARDIAN","OTHER").contains(g.relationship())||("OTHER".equals(g.relationship()))!=(trim(g.relationshipDetail())!=null)||!orders.add(g.displayOrder())||g.displayOrder()<0||!Set.of("SMS","KAKAO","EMAIL","MANUAL").contains(g.preferredChannel())||"EMAIL".equals(g.preferredChannel())&&trim(g.email())==null||trim(g.email())!=null&&!EMAIL.matcher(g.email().trim()).matches())fail("VALIDATION_ERROR");}for(int i=0;i<guardians.size();i++)if(!orders.contains(i))fail("VALIDATION_ERROR");}
    private void validateStatus(StudentRecord s,StatusWrite c){if(c==null||c.version()<0||!STATUSES.contains(c.toStatus())||trim(c.reason())==null||c.reason().trim().length()<5||c.reason().trim().length()>200||SENSITIVE_REASON.matcher(c.reason()).find())fail("VALIDATION_ERROR");if(c.effectiveDate()==null||c.effectiveDate().isAfter(LocalDate.now(clock.withZone(STUDIO_ZONE))))fail("STUDENT_STATUS_DATE_INVALID");boolean ok="ACTIVE".equals(s.status())&&Set.of("PAUSED","GRADUATED","DROPPED").contains(c.toStatus())||"PAUSED".equals(s.status())&&Set.of("ACTIVE","GRADUATED","DROPPED").contains(c.toStatus());if(!ok)fail("STUDENT_STATUS_TRANSITION_DENIED");if(c.version()!=s.version())fail("STUDENT_VERSION_CONFLICT");}
    private String token(UUID id,StatusWrite c,Impacts i,Instant at){String f=fingerprint(c),payload=id+"|"+c.version()+"|"+c.toStatus()+"|"+c.effectiveDate()+"|"+at.getEpochSecond()+"|"+f+"|"+hash(i);return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))+"."+protector.hash(payload);}
    private void verifyToken(UUID id,StatusWrite c,Impacts i){try{String[] t=c.previewToken().split("\\.");String p=new String(Base64.getUrlDecoder().decode(t[0]),StandardCharsets.UTF_8);String[] v=p.split("\\|",-1);if(t.length!=2||v.length!=7||!MessageDigest.isEqual(t[1].getBytes(),protector.hash(p).getBytes())||!v[0].equals(id.toString())||!v[1].equals(Long.toString(c.version()))||!v[2].equals(c.toStatus())||!v[3].equals(c.effectiveDate().toString())||!v[5].equals(fingerprint(c))||!v[6].equals(hash(i))||clock.instant().getEpochSecond()-Long.parseLong(v[4])>300)fail("STUDENT_STATUS_PREVIEW_REQUIRED");}catch(Exception e){if(e instanceof StudentException se)throw se;fail("STUDENT_STATUS_PREVIEW_REQUIRED");}}
    private String fingerprint(StatusWrite c){return hash(c.toStatus()+"|"+c.effectiveDate()+"|"+c.reason().trim()+"|"+c.version());}
    private NoteView note(NoteRecord n,UUID actor){boolean edit=n.createdBy().equals(actor)&&clock.instant().isBefore(n.createdAt().plus(Duration.ofHours(24)));return new NoteView(n.id(),n.studentId(),protector.reveal(n.contentCiphertext()),new CreatedBy(n.createdBy(),n.createdByName()),n.createdAt(),n.updatedAt(),n.updatedAt().isAfter(n.createdAt()),n.version(),edit?List.of("EDIT","HIDE"):List.of("HIDE"));}
    private Cursor decodeCursor(String value){if(value==null||value.isBlank())return new Cursor(null,null);try{String p=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);String[] v=p.split("\\|",-1);if(v.length!=3||!MessageDigest.isEqual(v[2].getBytes(),protector.hash(v[0]+"|"+v[1]).getBytes()))fail("STUDENT_NOTE_CURSOR_INVALID");return new Cursor(Instant.parse(v[0]),UUID.fromString(v[1]));}catch(Exception e){if(e instanceof StudentException se)throw se;fail("STUDENT_NOTE_CURSOR_INVALID");return null;}}
    private String encodeCursor(NoteRecord n){String p=n.createdAt()+"|"+n.id();return Base64.getUrlEncoder().withoutPadding().encodeToString((p+"|"+protector.hash(p)).getBytes(StandardCharsets.UTF_8));}
    private StudentCreate normalize(StudentCreate c){if(c==null)fail("VALIDATION_ERROR");return new StudentCreate(name(c.studentName()),c.birthday(),trim(c.schoolName()),c.joinedAt(),c.duplicateConfirmed(),normalizeGuardians(c.guardians()));}
    private StudentUpdate normalize(StudentUpdate c){if(c==null)fail("VALIDATION_ERROR");return new StudentUpdate(name(c.studentName()),c.birthday(),trim(c.schoolName()),c.joinedAt(),c.version(),normalizeGuardians(c.guardians()));}
    private List<GuardianWrite> normalizeGuardians(List<GuardianWrite> gs){if(gs==null)return null;return gs.stream().map(g->new GuardianWrite(g.id(),name(g.name()),g.relationship(),trim(g.relationshipDetail()),g.phone(),trim(g.email()),g.preferredChannel()==null?"SMS":g.preferredChannel(),g.primaryContact(),g.displayOrder())).toList();}
    private static String name(String s){String v=trim(s);return v==null?null:v.replaceAll("\\s+"," ");}private static String search(String s){return s.toLowerCase(Locale.ROOT).replaceAll("\\s+","");}private static String trim(String s){if(s==null)return null;String v=s.trim();return v.isEmpty()?null:v;}private static String phone(String s){if(s==null)fail("VALIDATION_ERROR");String d=s.replaceAll("[^0-9+]","");if(d.startsWith("010"))d="+82"+d.substring(1);if(d.startsWith("0"))d="+82"+d.substring(1);if(!d.matches("\\+[1-9][0-9]{7,14}"))fail("VALIDATION_ERROR");return d;}private static boolean mmdd(String v){try{MonthDay.parse("--"+v);return true;}catch(Exception e){return false;}}private static String maskEmail(String e){if(e==null)return null;int at=e.indexOf('@');return e.substring(0,Math.min(2,at))+"***"+e.substring(at);}private static List<String> actions(String s){return switch(s){case "ACTIVE"->List.of("EDIT","CHANGE_STATUS","MANAGE_SCHEDULE","MANAGE_TUITION");case "PAUSED"->List.of("EDIT","CHANGE_STATUS");default->List.of("EDIT");};}private static String content(String c){String v=trim(c);if(v==null||v.length()>2000)fail("VALIDATION_ERROR");return v;}private static String hash(Object o){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(o.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}private static void fail(String c){throw new StudentException(c);}
    private void event(UUID actor,RequestMetadata m,String task,String action,UUID target,Map<String,Object>d){audit.record(new Event(clock.instant(),m.requestId(),task,"OPERATION","ADMIN",actor,null,action,"STUDENT",target,"SUCCESS",null,m.ip(),m.userAgent(),d));}
    private record Cursor(Instant at,UUID id){} public record RequestMetadata(String requestId,String ip,String userAgent){}
}
