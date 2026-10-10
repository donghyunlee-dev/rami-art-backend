package com.ramiart.admin.lessonlog.application;

import static com.ramiart.admin.lessonlog.application.LessonLogModels.*;
import static com.ramiart.admin.lessonlog.application.LessonLogRepository.*;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.student.application.StudentDataProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonLogService {
    private final LessonLogRepository repository;
    private final AuditRecorder audit;
    private final StudentDataProtector protector;

    public LessonLogService(LessonLogRepository repository, AuditRecorder audit, StudentDataProtector protector) {
        this.repository=repository; this.audit=audit; this.protector=protector;
    }

    @Transactional(readOnly=true)
    public View get(UUID sessionId,Authentication auth) {
        UUID actor=actor(auth,"LESSON_LOG_READ");
        Session session=session(sessionId,false);
        scope(actor,session);
        return view(session,auth);
    }

    @Transactional(readOnly=true)
    public LessonLogPage list(String month, String keyword, String status, int page, int size, Authentication auth) {
        UUID actor=actor(auth,"LESSON_LOG_READ");
        if(month==null||!month.matches("\\d{4}-(0[1-9]|1[0-2])")||page<0||!(size==10||size==20||size==50))
            throw new LessonLogException("VALIDATION_ERROR");
        YearMonth selectedMonth;
        try { selectedMonth=YearMonth.parse(month); }
        catch(RuntimeException exception) { throw new LessonLogException("VALIDATION_ERROR"); }
        String normalizedStatus=status==null?"ALL":status;
        if(!Set.of("ALL","MISSING","DRAFT","FINALIZED").contains(normalizedStatus))
            throw new LessonLogException("VALIDATION_ERROR");
        String normalizedKeyword=trim(keyword);
        if(normalizedKeyword!=null&&normalizedKeyword.length()>50) throw new LessonLogException("VALIDATION_ERROR");
        ListQuery query=new ListQuery(selectedMonth.atDay(1),selectedMonth.plusMonths(1).atDay(1),normalizedKeyword,
                normalizedStatus,actor,repository.isOwner(actor),page,size);
        long total=repository.countList(query);
        List<ListItem> items=repository.listSessions(query);
        int totalPages=(int)((total+size-1)/size);
        return new LessonLogPage(page,size,total,totalPages,items);
    }

    @Transactional
    public View createDraft(UUID sessionId,Authentication auth,RequestMetadata metadata) {
        UUID actor=actor(auth,"LESSON_LOG_WRITE");
        Session session=session(sessionId,true);
        scope(actor,session);
        if(!"CLOSED".equals(session.status())) throw new LessonLogException("LESSON_LOG_SESSION_NOT_CLOSED");
        List<StoredLog> logs=repository.revisions(sessionId,true);
        if(logs.stream().anyMatch(l->"DRAFT".equals(l.status()))) return view(session,auth);
        if(!logs.isEmpty()) throw new LessonLogException("LESSON_LOG_FINALIZED");
        List<Target> targets=repository.attendanceTargets(sessionId);
        if(targets.isEmpty()||targets.stream().anyMatch(t->t.attendanceStatus()==null))
            throw new LessonLogException("LESSON_LOG_ATTENDANCE_INCOMPLETE");
        PlanItem plan=session.planItemId()==null?null:repository.findPlanItem(session.planItemId())
                .orElseThrow(()->new LessonLogException("LESSON_LOG_PLAN_NOT_FOUND"));
        UUID id=repository.insertDraft(session,plan,actor,null,null,null,null);
        event(actor,metadata,"LESSON_LOG_DRAFT_CREATED",id,Map.of("revision",1));
        return view(session,auth);
    }

    @Transactional
    public View save(UUID logId,SaveWrite raw,Authentication auth,RequestMetadata metadata) {
        UUID actor=actor(auth,"LESSON_LOG_WRITE");
        if(raw==null) throw new LessonLogException("VALIDATION_ERROR");
        StoredLog log=repository.findLog(logId,true).orElseThrow(()->new LessonLogException("LESSON_LOG_NOT_FOUND"));
        if(!"DRAFT".equals(log.status())) throw new LessonLogException("LESSON_LOG_FINALIZED");
        Session session=session(log.sessionId(),true); scope(actor,session);
        SaveWrite write=normalize(raw);
        List<Target> targets=repository.attendanceTargets(session.id());
        List<StudentWriteData> records=validateRecords(write.studentRecords(),targets);
        if(!Objects.equals(write.planItemId(),session.planItemId()))
            throw new LessonLogException("LESSON_LOG_PLAN_MISMATCH");
        if(write.planItemId()!=null) {
            PlanItem plan=repository.findPlanItem(write.planItemId()).orElseThrow(()->new LessonLogException("LESSON_LOG_PLAN_NOT_FOUND"));
            if(!Objects.equals(session.planItemId(),plan.id())) throw new LessonLogException("LESSON_LOG_PLAN_MISMATCH");
            if((!write.actualTitle().equals(plan.title())||!write.activities().equals(plan.activities())
                    ||!write.materials().equals(plan.materials()))
                    && write.changeReason()==null) throw new LessonLogException("LESSON_LOG_CHANGE_REASON_REQUIRED");
        }
        byte[] overallNote=protect(write.overallNote());
        if(repository.updateDraft(logId,write.version(),write,overallNote)!=1)
            throw new LessonLogException("LESSON_LOG_VERSION_CONFLICT");
        repository.replaceStudentRecords(logId,records);
        event(actor,metadata,"LESSON_LOG_DRAFT_SAVED",logId,Map.of("revision",log.revision(),"recordCount",records.size()));
        return view(session,auth);
    }

    @Transactional
    public View finalizeLog(UUID logId,FinalizeWrite command,UUID key,Authentication auth,RequestMetadata metadata) {
        UUID actor=actor(auth,"LESSON_LOG_WRITE");
        if(command==null||command.version()<0||key==null) throw new LessonLogException("VALIDATION_ERROR");
        StoredLog log=repository.findLog(logId,true).orElseThrow(()->new LessonLogException("LESSON_LOG_NOT_FOUND"));
        Session session=session(log.sessionId(),true); scope(actor,session);
        String scope="LESSON_LOG_FINALIZE:"+logId;
        String hash=hash(logId+"|"+command.version());
        if(!repository.claimIdempotency(scope,key,hash)) {
            if(!repository.idempotencyHash(scope,key).filter(hash::equals).isPresent())
                throw new LessonLogException("IDEMPOTENCY_KEY_REUSED");
            UUID resource=repository.idempotencyResource(scope,key).orElseThrow(()->new LessonLogException("IDEMPOTENCY_IN_PROGRESS"));
            return view(resourceSession(resource),auth,resource);
        }
        if(!"DRAFT".equals(log.status())) throw new LessonLogException("LESSON_LOG_FINALIZED");
        if(log.version()!=command.version()) throw new LessonLogException("LESSON_LOG_VERSION_CONFLICT");
        View current=view(session,auth);
        if(!attendanceMatches(current.attendanceTargets(),current.currentLog().studentRecords()))
            throw new LessonLogException("LESSON_LOG_ATTENDANCE_CHANGED");
        if(!current.finalizable()) throw new LessonLogException("LESSON_LOG_INCOMPLETE",
                Map.of("missingRequiredStudentIds",current.missingRequiredStudentIds()));
        Instant now=Instant.now();
        if(repository.finalizeDraft(logId,command.version(),actor,now)!=1)
            throw new LessonLogException("LESSON_LOG_VERSION_CONFLICT");
        event(actor,metadata,"LESSON_LOG_FINALIZED",logId,Map.of("revision",log.revision()));
        repository.completeIdempotency(scope,key,logId,200);
        return view(session,auth);
    }

    @Transactional
    public View amend(UUID logId,AmendmentWrite command,Authentication auth,RequestMetadata metadata) {
        UUID actor=actor(auth,"LESSON_LOG_WRITE");
        String reason=trim(command==null?null:command.amendReason());
        if(reason==null||reason.length()<5||reason.length()>200) throw new LessonLogException("VALIDATION_ERROR");
        StoredLog source=repository.findLog(logId,true).orElseThrow(()->new LessonLogException("LESSON_LOG_NOT_FOUND"));
        if(!"FINALIZED".equals(source.status())) throw new LessonLogException("LESSON_LOG_FINALIZED");
        Session session=session(source.sessionId(),true); scope(actor,session);
        if(repository.revisions(session.id(),true).stream().anyMatch(l->"DRAFT".equals(l.status())))
            throw new LessonLogException("LESSON_LOG_DRAFT_EXISTS");
        List<StoredStudent> records=repository.studentRecords(source.id());
        if(repository.amendPrevious(source.id())!=1) throw new LessonLogException("LESSON_LOG_VERSION_CONFLICT");
        UUID draft=repository.insertDraft(session,repository.findPlanItem(source.planItemId()).orElse(null),actor,
                reason,source.id(),source,records);
        event(actor,metadata,"LESSON_LOG_AMENDMENT_CREATED",draft,Map.of("basedOnLogId",source.id().toString(),"reasonLength",reason.length()));
        return view(session,auth);
    }

    private Session resourceSession(UUID logId) {
        StoredLog log=repository.findLog(logId,false).orElseThrow(()->new LessonLogException("LESSON_LOG_NOT_FOUND"));
        return session(log.sessionId(),false);
    }

    private List<StudentWriteData> validateRecords(List<StudentRecordWrite> writes,List<Target> targets) {
        if(writes==null||writes.size()!=targets.size()) throw new LessonLogException("LESSON_LOG_STUDENT_SET_INVALID");
        Map<UUID,Target> expected=new LinkedHashMap<>(); targets.forEach(t->expected.put(t.studentId(),t));
        Set<UUID> seen=new HashSet<>(); List<StudentWriteData> values=new ArrayList<>();
        for(StudentRecordWrite write:writes) {
            Target target=write==null?null:expected.get(write.studentId());
            if(target==null||!seen.add(write.studentId())) throw new LessonLogException("LESSON_LOG_STUDENT_NOT_TARGET");
            if(!validAttendance(target.attendanceStatus()))
                throw new LessonLogException("LESSON_LOG_ATTENDANCE_INCOMPLETE");
            if(!Objects.equals(target.attendanceStatus(),write.attendanceStatusSnapshot())) throw new LessonLogException("LESSON_LOG_ATTENDANCE_CHANGED");
            String participation=trim(write.participation()),progress=content(write.progressNote(),2000),observation=content(write.observation(),2000),absence=content(write.absenceNote(),500);
            boolean absent="ABSENT".equals(target.attendanceStatus())||"EXCUSED".equals(target.attendanceStatus());
            if(absent&&(participation!=null||progress!=null||observation!=null)||!absent&&absence!=null)
                throw new LessonLogException("LESSON_LOG_STUDENT_RECORD_INVALID");
            if(participation!=null&&!Set.of("LOW","NORMAL","HIGH").contains(participation)) throw new LessonLogException("VALIDATION_ERROR");
            List<UUID> assets=write.artworkAssetIds()==null?List.of():write.artworkAssetIds();
            if(assets.size()>10||new HashSet<>(assets).size()!=assets.size()||!repository.artworkAssetsReady(assets))
                throw new LessonLogException("LESSON_LOG_MEDIA_NOT_READY");
            values.add(new StudentWriteData(UUID.randomUUID(),write.studentId(),target.attendanceStatus(),participation,
                    protect(progress),protect(observation),protect(absence),assets));
        }
        return values;
    }

    private View view(Session session,Authentication auth) {
        return view(session,auth,null);
    }

    private View view(Session session,Authentication auth,UUID preferredLogId) {
        List<Target> targets=repository.attendanceTargets(session.id());
        List<StoredLog> logs=repository.revisions(session.id(),false);
        StoredLog current=preferredLogId==null?logs.stream().filter(l->"DRAFT".equals(l.status())).findFirst()
                .orElseGet(()->logs.stream().filter(l->"FINALIZED".equals(l.status())).findFirst().orElse(logs.isEmpty()?null:logs.getFirst()))
                :logs.stream().filter(l->l.id().equals(preferredLogId)).findFirst()
                    .orElseThrow(()->new LessonLogException("LESSON_LOG_NOT_FOUND"));
        Log log=current==null?null:toLog(current,auth);
        List<UUID> missing=log==null?targets.stream().filter(t->required(t.attendanceStatus())).map(Target::studentId).toList()
                :log.studentRecords().stream().filter(r->required(r.attendanceStatusSnapshot())
                    &&r.participation()==null&&r.progressNote()==null).map(StudentRecord::studentId).toList();
        boolean finalizable=log!=null&&"DRAFT".equals(log.status())&&!log.actualTitle().isBlank()&&!log.activities().isEmpty()
                &&missing.isEmpty()&&attendanceMatches(targets,log.studentRecords());
        PlanItem plan=session.planItemId()==null?null:repository.findPlanItem(session.planItemId()).orElse(null);
        return new View(session,repository.assignedStaff(session.classGroupId(),session.date()),targets,plan,log,
                logs.stream().map(this::revision).toList(),missing,finalizable);
    }

    private Log toLog(StoredLog l,Authentication auth) {
        List<StudentRecord> records=repository.studentRecords(l.id()).stream().map(r->new StudentRecord(r.id(),r.studentId(),r.attendanceStatus(),
                r.participation(),reveal(r.progressCiphertext()),reveal(r.observationCiphertext()),reveal(r.absenceCiphertext()),repository.artworkAssets(r.id()))).toList();
        return new Log(l.id(),l.sessionId(),l.revision(),l.status(),l.basedOnLogId(),l.planItemId(),l.actualTitle(),l.activities(),l.materials(),
                l.changeReason(),reveal(l.overallNoteCiphertext()),l.amendReason(),l.version(),l.createdBy(),l.createdByName(),l.createdAt(),
                l.finalizedBy(),l.finalizedByName(),l.finalizedAt(),records);
    }
    private Revision revision(StoredLog l) { return new Revision(l.id(),l.revision(),l.status(),l.amendReason(),l.createdBy(),l.createdByName(),l.createdAt(),l.finalizedBy(),l.finalizedByName(),l.finalizedAt()); }
    private Session session(UUID id,boolean lock) { return repository.findSession(id,lock).orElseThrow(()->new LessonLogException("LESSON_LOG_SESSION_NOT_FOUND")); }
    private void scope(UUID actor,Session session) { if(!repository.isOwner(actor)&&!repository.managesSession(actor,session.classGroupId(),session.date())) throw new LessonLogException("LESSON_LOG_SCOPE_DENIED"); }
    private byte[] protect(String value) { return value==null?null:protector.protect(value).ciphertext(); }
    private String reveal(byte[] value) { return value==null?null:protector.reveal(value); }
    private static SaveWrite normalize(SaveWrite raw) {
        if(raw.version()<0||trim(raw.actualTitle())==null||trim(raw.actualTitle()).length()>120||raw.activities()==null||raw.activities().size()>20||raw.materials()==null||raw.materials().size()>30)
            throw new LessonLogException("VALIDATION_ERROR");
        List<String> activities=raw.activities().stream().map(LessonLogService::trim).toList();
        List<String> materials=raw.materials().stream().map(LessonLogService::trim).toList();
        if(activities.stream().anyMatch(Objects::isNull)||materials.stream().anyMatch(Objects::isNull)) throw new LessonLogException("VALIDATION_ERROR");
        String change=content(raw.changeReason(),500);
        return new SaveWrite(raw.version(),raw.planItemId(),trim(raw.actualTitle()),activities,materials,change,
                content(raw.overallNote(),2000),raw.studentRecords());
    }
    private static String trim(String value) { if(value==null)return null; String s=value.trim(); return s.isEmpty()?null:s; }
    private static boolean required(String status) { return "PRESENT".equals(status)||"LATE".equals(status); }
    private static boolean validAttendance(String status) {
        return required(status)||"ABSENT".equals(status)||"EXCUSED".equals(status);
    }
    private static boolean attendanceMatches(List<Target> targets,List<StudentRecord> records) {
        if(targets.size()!=records.size()) return false;
        Map<UUID,String> statuses=new LinkedHashMap<>();
        records.forEach(record->statuses.put(record.studentId(),record.attendanceStatusSnapshot()));
        return targets.stream().allMatch(target->validAttendance(target.attendanceStatus())
                &&Objects.equals(statuses.get(target.studentId()),target.attendanceStatus()));
    }
    private static String content(String value,int max) { String s=trim(value); if(s!=null&&s.length()>max) throw new LessonLogException("VALIDATION_ERROR"); return s; }
    private static UUID actor(Authentication auth,String permission) {
        if(auth==null||!auth.isAuthenticated()) throw new LessonLogException("SESSION_REQUIRED");
        if(auth.getAuthorities().stream().noneMatch(a->permission.equals(a.getAuthority()))) throw new LessonLogException(permission.equals("LESSON_LOG_READ")?"LESSON_LOG_READ_DENIED":"LESSON_LOG_WRITE_DENIED");
        try { return UUID.fromString(auth.getName()); } catch(Exception ex) { throw new LessonLogException("SESSION_REQUIRED"); }
    }
    private void event(UUID actor,RequestMetadata m,String action,UUID target,Map<String,Object> details) {
        audit.record(new Event(Instant.now(),m.requestId(),"MGT-LESSON-LOG","OPERATION","ADMIN",actor,
                repository.adminDisplayName(actor),action,"LESSON_LOG",target,"SUCCESS",null,m.ipAddress(),m.userAgent(),details));
    }
    private static String hash(String s) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }

    public static final class LessonLogException extends RuntimeException {
        private final String code; private final Map<String,Object> details;
        public LessonLogException(String code) { this(code,Map.of()); }
        public LessonLogException(String code,Map<String,Object> details) { this.code=code;this.details=Map.copyOf(details); }
        public String code(){return code;} public Map<String,Object> details(){return details;}
    }
}
