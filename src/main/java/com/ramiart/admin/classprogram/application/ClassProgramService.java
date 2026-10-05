package com.ramiart.admin.classprogram.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import static com.ramiart.admin.classprogram.application.ClassProgramModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassProgramService {
    public record RequestMetadata(String requestId,String ipAddress,String userAgent) {}
    private final ClassProgramRepository repository; private final AuditRecorder audit; private final Clock clock;
    public ClassProgramService(ClassProgramRepository repository,AuditRecorder audit,Clock clock){this.repository=repository;this.audit=audit;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ProgramList list(Authentication auth){require(auth,"CONTENT_PROGRAM_READ"); List<CoursePrograms> rows=repository.findAll();
        List<Stored> visible=rows.stream().map(CoursePrograms::currentPublished).filter(Objects::nonNull).filter(Stored::visible).toList();
        Map<Integer,Long> counts=new HashMap<>();visible.forEach(p->counts.merge(p.displayOrder(),1L,Long::sum));
        List<Integer> conflicts=counts.entrySet().stream().filter(e->e.getValue()>1).map(Map.Entry::getKey).sorted().toList();
        return new ProgramList(rows.stream().map(this::item).toList(),new PublishValidation(visible.size(),conflicts,List.of())); }
    @Transactional public Mutation<ProgramItem> create(UUID courseId,UUID key,Authentication auth,RequestMetadata meta){UUID actor=require(auth,"CONTENT_PROGRAM_WRITE");String scope=actor+":CLASS_PROGRAM_CREATE:"+courseId;
        var claim=repository.claim(scope,key,hash("POST:/api/admin/content/class-programs/"+courseId+"/drafts"));if(!claim.claimed())return new Mutation<>(itemForCourse(courseId),true);
        if(!repository.courseExists(courseId))throw new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND");
        if(repository.findCourseStatus(courseId,"DRAFT").isPresent())throw new ClassProgramException("CLASS_PROGRAM_DRAFT_EXISTS");
        Stored published=repository.findCourseStatus(courseId,"PUBLISHED").orElse(null);UUID id=repository.createDraft(courseId,repository.nextRevision(courseId),actor,published==null?null:published.id());
        repository.complete(scope,key,id,201);audit(actor,meta,"CREATE_DRAFT",id,Map.of("courseId",courseId));return new Mutation<>(itemForCourse(courseId),false); }
    @Transactional public ProgramItem save(UUID courseId,UUID id,ProgramWrite write,UUID key,Authentication auth,RequestMetadata meta){UUID actor=require(auth,"CONTENT_PROGRAM_WRITE");write=normalize(write);validate(write);
        Stored current=repository.find(id).orElseThrow(()->new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND"));String scope=actor+":CLASS_PROGRAM_UPDATE:"+id;String requestHash=hash(write.toString());
        if(!current.courseId().equals(courseId))throw new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND");
        var claim=repository.claim(scope,key,requestHash);if(!claim.claimed())return itemForCourse(courseId);
        if(!"DRAFT".equals(current.status()))throw new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND");if(current.version()!=write.version())throw new ClassProgramException("CLASS_PROGRAM_VERSION_CONFLICT");
        if(repository.save(id,write)!=1)throw new ClassProgramException("CLASS_PROGRAM_VERSION_CONFLICT");repository.saveReferences(id,write.mediaAssetId());repository.complete(scope,key,id,200);
        audit(actor,meta,"UPDATE_DRAFT",id,Map.of("version",write.version()+1));return itemForCourse(courseId); }
    @Transactional(readOnly=true) public Preview preview(Authentication auth){require(auth,"CONTENT_PROGRAM_READ");return new Preview(repository.findAll().stream().map(this::item).toList());}
    @Transactional public Mutation<Publication> publish(PublishRequest body,UUID key,Authentication auth,RequestMetadata meta){UUID actor=require(auth,"CONTENT_PROGRAM_WRITE");if(body==null||body.draftId()==null||body.version()==null)throw new ClassProgramException("VALIDATION_ERROR");
        Stored draft=repository.find(body.draftId()).orElseThrow(()->new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND"));String scope=actor+":CLASS_PROGRAM_PUBLISH:"+draft.courseId();String reqHash=hash(body.toString());
        var claim=repository.claim(scope,key,reqHash);if(!claim.claimed()){Stored prior=repository.find(claim.resourceId()).orElseThrow();return new Mutation<>(publication(prior),true);}
        if(!"DRAFT".equals(draft.status()))throw new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND");if(!Objects.equals(body.version(),draft.version()))throw new ClassProgramException("CLASS_PROGRAM_VERSION_CONFLICT");
        if(draft.visible()){if(blank(draft.audienceLabel())||blank(draft.title())||blank(draft.description())||draft.activities().isEmpty()||draft.mediaAssetId()==null||blank(draft.altText()))throw new ClassProgramException("CLASS_PROGRAM_INCOMPLETE");
            boolean orderUsed=repository.findAll().stream().map(CoursePrograms::currentPublished).filter(Objects::nonNull).anyMatch(p->p.visible()&&!p.courseId().equals(draft.courseId())&&p.displayOrder()==draft.displayOrder());if(orderUsed)throw new ClassProgramException("CLASS_PROGRAM_ORDER_CONFLICT");}
        long remaining=repository.findAll().stream().map(CoursePrograms::currentPublished).filter(Objects::nonNull).filter(Stored::visible).filter(p->!p.courseId().equals(draft.courseId())).count();if(remaining==0&&!draft.visible())throw new ClassProgramException("CLASS_PROGRAM_VISIBLE_REQUIRED");
        repository.publish(draft.id(),draft.version(),actor);Stored published=repository.find(draft.id()).orElseThrow();repository.complete(scope,key,draft.id(),201);audit(actor,meta,"PUBLISH",draft.id(),Map.of("revision",draft.revision()));return new Mutation<>(publication(published),false); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public List<PublicProgram> publicPrograms(){return repository.publicPrograms();}
    private ProgramItem itemForCourse(UUID course){return repository.findAll().stream().filter(x->x.id().equals(course)).findFirst().map(this::item).orElseThrow(()->new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND"));}
    private ProgramItem item(CoursePrograms p){return new ProgramItem(new CourseView(p.id(),p.code(),p.name(),p.active()),revision(p.currentPublished()),revision(p.draft()),media(p.draft()!=null?p.draft():p.currentPublished()),List.of("EDIT"));}
    private static ProgramRevision revision(Stored p){return p==null?null:new ProgramRevision(p.id(),p.revision(),p.version(),p.audienceLabel(),p.title(),p.description(),p.activities(),p.mediaAssetId(),p.altText(),p.visible(),p.displayOrder(),p.updatedAt(),p.publishedAt(),!blank(p.title())&&!blank(p.description())&&!p.activities().isEmpty()&&p.mediaAssetId()!=null&&!blank(p.altText()));}
    private static Object media(Stored p){return p==null||p.mediaAssetId()==null?null:Map.of("id",p.mediaAssetId(),"publicUrl",p.imageUrl()==null?"":p.imageUrl(),"fileName","","mimeType",p.mimeType()==null?"image/jpeg":p.mimeType(),"fileSize",p.fileSize(),"width",p.width(),"height",p.height(),"status","READY","expiresAt",p.expiresAt()==null?java.time.Instant.EPOCH:p.expiresAt());}
    private static Publication publication(Stored p){return new Publication(p.id(),p.courseId(),p.revision(),p.status(),p.version(),p.publishedAt());}
    private void validate(ProgramWrite w){if(w==null||w.version()==null||w.visible()==null||w.displayOrder()==null||w.displayOrder()<0)throw new ClassProgramException("VALIDATION_ERROR");
        if(length(w.audienceLabel(),100)||length(w.title(),100)||length(w.description(),500)||length(w.altText(),300)||w.activities()==null||w.activities().size()>10||w.activities().stream().anyMatch(a->a==null||a.isBlank()||a.length()>100))throw new ClassProgramException("CLASS_PROGRAM_INCOMPLETE");}
    private ProgramWrite normalize(ProgramWrite w){if(w==null)return null;List<String> activities=w.activities()==null?null:w.activities().stream().filter(Objects::nonNull).map(String::trim).filter(a->!a.isEmpty()).toList();return new ProgramWrite(w.version(),trim(w.audienceLabel()),trim(w.title()),trim(w.description()),activities,w.mediaAssetId(),trim(w.altText()),w.visible(),w.displayOrder());}
    private static String trim(String s){if(s==null)return null;String t=s.trim();return t.isEmpty()?null:t;}
    private static boolean length(String s,int max){return s!=null&&s.trim().length()>max;}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static UUID require(Authentication a,String p){if(a==null||a.getAuthorities().stream().noneMatch(x->p.equals(x.getAuthority())))throw new ClassProgramException("CONTENT_PROGRAM_DENIED");try{return UUID.fromString(a.getName());}catch(RuntimeException e){throw new ClassProgramException("CONTENT_PROGRAM_DENIED");}}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void audit(UUID actor,RequestMetadata m,String action,UUID id,Map<String,Object> detail){audit.record(new Event(clock.instant(),m.requestId(),"MGT-CONTENT-CLASS-PROGRAM","OPERATION","ADMIN",actor,null,action,"CLASS_PROGRAM",id,"SUCCESS",null,m.ipAddress(),m.userAgent(),detail));}
}
