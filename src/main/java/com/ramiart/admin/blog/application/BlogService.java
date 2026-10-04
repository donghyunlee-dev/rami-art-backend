package com.ramiart.admin.blog.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.blog.application.BlogModels.*;
import com.ramiart.admin.blog.application.BlogRepository.Revision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlogService {
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
    private static final List<String> CATEGORIES = List.of("CLASS_STORY","STUDIO_NEWS","ARTWORK_STORY");
    private final BlogRepository repository;
    private final BlogContentSanitizer sanitizer;
    private final AuditRecorder audit;
    private final Clock clock;
    public BlogService(BlogRepository repository, BlogContentSanitizer sanitizer, AuditRecorder audit, Clock clock) {
        this.repository=repository; this.sanitizer=sanitizer; this.audit=audit; this.clock=clock;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page<Summary> list(String keyword, List<String> categories, List<String> states, String sort, int page, int size) {
        keyword=trim(keyword);
        if (keyword != null && (keyword.codePointCount(0,keyword.length())<2 || keyword.codePointCount(0,keyword.length())>50)
                || page<0 || !List.of(10,20,50).contains(size) || !List.of("UPDATED_DESC","PUBLISHED_DESC","TITLE_ASC").contains(sort)
                || categories.stream().anyMatch(c->!CATEGORIES.contains(c))
                || states.stream().anyMatch(s->!List.of("DRAFT_ONLY","PUBLISHED_VISIBLE","PUBLISHED_HIDDEN","HAS_DRAFT").contains(s)))
            throw new BlogException("BLOG_QUERY_INVALID");
        long total=repository.countAdmin(keyword,categories,states);
        List<Summary> rows=repository.listAdmin(keyword,categories,states,sort,page,size).stream().map(r->summary(r)).toList();
        return new Page<>(page,size,total,(int)Math.ceil((double)total/size),sort,rows);
    }

    @Transactional
    public Created create(Write body, UUID actor, UUID key, RequestMetadata metadata) {
        Write command=normalize(body);
        validateDraft(command);
        String scope=actor+":POST:BLOG_CREATE";
        var claim=repository.claim(scope,key,hash(command));
        if (!claim.claimed()) return created(repository.findRevision(claim.resourceId()).orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND")));
        if(command.mediaAssetId()!=null && !repository.mediaReady(command.mediaAssetId())) throw new BlogException("BLOG_MEDIA_NOT_READY");
        UUID post=UUID.randomUUID(), id=UUID.randomUUID();
        Revision draft=repository.insertDraft(post,id,1,null,command,actor);
        repository.updateDraftReference(id,null,command.mediaAssetId());
        audit(actor,metadata,"BLOG_POST_DRAFT_CREATED",post,Map.of("revision",1));
        repository.complete(scope,key,id,201);
        return created(draft);
    }

    @Transactional(readOnly=true)
    public Detail detail(UUID postId,String mode) {
        if(!List.of("DRAFT","PUBLISHED").contains(mode)) throw new BlogException("BLOG_QUERY_INVALID");
        Revision row;
        boolean editable=true;
        if("DRAFT".equals(mode)) {
            var draft=repository.findByPostAndStatus(postId,"DRAFT");
            if(draft.isPresent()) row=draft.get();
            else { row=repository.findByPostAndStatus(postId,"PUBLISHED").orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND")); editable=false; }
        } else { row=repository.findByPostAndStatus(postId,"PUBLISHED").orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND")); editable=false; }
        return detail(row,editable);
    }

    @Transactional
    public Created createDraft(UUID postId,UUID actor,UUID key,RequestMetadata metadata) {
        String scope=actor+":POST:BLOG_DRAFT:"+postId;
        var claim=repository.claim(scope,key,hash(postId));
        if(!claim.claimed()) return created(repository.findRevision(claim.resourceId()).orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND")));
        if(repository.findByPostAndStatus(postId,"DRAFT").isPresent()) throw new BlogException("BLOG_DRAFT_EXISTS");
        Revision base=repository.findByPostAndStatus(postId,"PUBLISHED").orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND"));
        Write copy=new Write(base.title(),base.summary(),base.category(),base.content(),base.mediaId(),base.altText(),base.visible());
        UUID id=UUID.randomUUID();
        Revision draft=repository.insertDraft(postId,id,nextRevision(postId),base.id(),copy,actor);
        repository.updateDraftReference(id,null,copy.mediaAssetId());
        audit(actor,metadata,"BLOG_POST_DRAFT_CREATED",postId,Map.of("revision",draft.revision(),"basedOnRevisionId",base.id()));
        repository.complete(scope,key,id,201);
        return created(draft);
    }

    @Transactional
    public Saved save(UUID postId,UUID draftId,Save raw,UUID actor,UUID key,RequestMetadata metadata) {
        Save body=normalize(raw);
        validateDraft(new Write(body.title(),body.summary(),body.category(),body.content(),body.mediaAssetId(),body.altText(),body.visible()));
        if(body.mediaAssetId()!=null&&!repository.mediaReady(body.mediaAssetId())) throw new BlogException("BLOG_MEDIA_NOT_READY");
        String scope=actor+":PUT:BLOG_DRAFT:"+draftId;
        var claim=repository.claim(scope,key,hash(body));
        if(!claim.claimed()) return saved(repository.findRevision(claim.resourceId()).orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND")));
        Revision current=repository.findRevision(draftId).filter(r->r.postId().equals(postId)&&r.status().equals("DRAFT"))
                .orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND"));
        if(current.version()!=body.version() || repository.saveDraft(draftId,body)!=1) throw new BlogException("BLOG_DRAFT_VERSION_CONFLICT");
        repository.updateDraftReference(draftId,current.mediaId(),body.mediaAssetId());
        Revision updated=repository.findRevision(draftId).orElseThrow();
        audit(actor,metadata,"BLOG_POST_DRAFT_SAVED",postId,Map.of("revision",updated.revision(),"version",updated.version()));
        repository.complete(scope,key,draftId,200);
        return saved(updated);
    }

    @Transactional(readOnly=true)
    public Detail preview(UUID postId,UUID draftId,long version) {
        Revision row=repository.findRevision(draftId).filter(r->r.postId().equals(postId)&&r.status().equals("DRAFT"))
                .orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND"));
        if(row.version()!=version) throw new BlogException("BLOG_DRAFT_VERSION_CONFLICT");
        return detail(row,true);
    }

    @Transactional
    public Publication publish(UUID postId,Publish body,UUID actor,UUID key,RequestMetadata metadata) {
        String scope=actor+":POST:BLOG_PUBLISH:"+postId;
        var claim=repository.claim(scope,key,hash(body));
        if(!claim.claimed()) {
            Revision replay=repository.findRevision(claim.resourceId()).orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND"));
            return publication(replay);
        }
        Revision draft=repository.findRevision(body.draftId()).filter(r->r.postId().equals(postId)&&r.status().equals("DRAFT"))
                .orElseThrow(()->missing("BLOG_POST_REVISION_NOT_FOUND"));
        if(draft.version()!=body.version()) throw new BlogException("BLOG_DRAFT_VERSION_CONFLICT");
        String canonical=sanitizer.sanitize(draft.content());
        boolean publishable=draft.title()!=null&&draft.category()!=null&&(draft.visible()
                ? draft.summary()!=null&&draft.mediaId()!=null&&draft.altText()!=null&&canonical!=null&&!canonical.isBlank()
                : canonical!=null&&!canonical.isBlank());
        if(!publishable) throw new BlogException("BLOG_NOT_PUBLISHABLE");
        if(draft.mediaId()!=null&&!repository.mediaReady(draft.mediaId())) throw new BlogException("BLOG_MEDIA_NOT_READY");
        repository.archivePublished(postId);
        repository.publishDraft(draft.id(),actor);
        repository.publishReference(draft.id());
        Revision published=repository.findRevision(draft.id()).orElseThrow();
        audit(actor,metadata,"BLOG_POST_PUBLISHED",postId,Map.of("revision",published.revision(),"visible",published.visible()));
        repository.complete(scope,key,draft.id(),200);
        return publication(published);
    }

    @Transactional(readOnly=true)
    public Page<PublicItem> publicList(String category,String keyword,int page,int size) {
        keyword=trim(keyword);
        if(category!=null&&!CATEGORIES.contains(category)||keyword!=null&&(keyword.codePointCount(0,keyword.length())<2||keyword.codePointCount(0,keyword.length())>50)
                ||page<1||size<1||size>30) throw new BlogException("BLOG_QUERY_INVALID");
        long count=repository.countPublic(category,keyword);
        List<PublicItem> items=repository.listPublic(category,keyword,page,size).stream().map(r->new PublicItem(r.postId(),r.title(),r.summary(),
                sanitizer.sanitize(r.content()),r.category(),media(r),r.publishedAt())).toList();
        return new Page<>(page,size,count,(int)Math.ceil((double)count/size),"PUBLISHED_DESC",items);
    }

    private int nextRevision(UUID postId) { return repository.nextRevision(postId); }
    private Summary summary(Revision r) {
        var d=repository.findByPostAndStatus(r.postId(),"DRAFT").orElse(null);
        var p=repository.findByPostAndStatus(r.postId(),"PUBLISHED").orElse(null);
        Revision display=d!=null?d:p!=null?p:r;
        Thumbnail thumbnail=display.mediaId()==null?null:new Thumbnail(display.mediaId(),display.mediaUrl(),display.altText(),d!=null?"DRAFT":"PUBLISHED");
        return new Summary(r.postId(),display.title(),display.category(),thumbnail,author(display),
                p==null?null:new Published(p.id(),p.revision(),p.visible(),p.publishedAt()),
                d==null?null:new Draft(d.id(),d.revision(),d.updatedAt(),d.version(),publishable(d)),
                new ListActions(d!=null,d==null&&p!=null,d!=null&&publishable(d)),display.updatedAt());
    }
    private Detail detail(Revision r,boolean editable) {
        String sanitized=sanitizer.sanitize(r.content());
        return new Detail(r.postId(),r.id(),"DRAFT".equals(r.status())?r.id():null,r.revision(),r.status(),r.status(),editable,
                r.version(),r.title(),r.summary(),r.category(),sanitized,media(r),r.altText(),r.visible(),author(r),publishable(r),
                new Actions(!editable&&"PUBLISHED".equals(r.status()),editable,editable,editable&&publishable(r)),r.updatedAt());
    }
    private boolean publishable(Revision r) { return r.title()!=null&&r.category()!=null&&(r.visible()
            ?r.summary()!=null&&r.mediaId()!=null&&r.altText()!=null&&r.content()!=null&&!r.content().isBlank()
            :r.content()!=null&&!r.content().isBlank()); }
    private static Media media(Revision r) { return r.mediaId()==null?null:new Media(r.mediaId(),r.mediaUrl(),r.mediaStatus()); }
    private static Author author(Revision r) { return new Author(r.authorId(),r.authorName(),r.authorActive()); }
    private static Created created(Revision r) { return new Created(r.postId(),r.id(),r.revision(),r.version(),r.status()); }
    private static Saved saved(Revision r) { return new Saved(r.postId(),r.id(),r.version(),publishableStatic(r),r.updatedAt()); }
    private static boolean publishableStatic(Revision r) { return r.title()!=null&&r.category()!=null&&r.content()!=null&&!r.content().isBlank(); }
    private static Publication publication(Revision r) { return new Publication(r.postId(),r.id(),r.revision(),r.visible(),r.publishedAt()); }
    private static BlogException missing(String code) { return new BlogException(code); }
    private Write normalize(Write b) {
        return new Write(trim(b.title()),trim(b.summary()),b.category(),sanitizer.sanitize(b.content()),b.mediaAssetId(),trim(b.altText()),b.visible()==null?false:b.visible());
    }
    private Save normalize(Save b) { return new Save(b.version(),trim(b.title()),trim(b.summary()),b.category(),sanitizer.sanitize(b.content()),b.mediaAssetId(),trim(b.altText()),b.visible()); }
    private void validateDraft(Write b) {
        if(len(b.title())>100||len(b.summary())>300||len(b.altText())>300||b.category()!=null&&!CATEGORIES.contains(b.category()))
            throw new BlogException("BLOG_CONTENT_INVALID");
        sanitizer.sanitize(b.content());
    }
    private void audit(UUID actor,RequestMetadata m,String action,UUID target,Map<String,Object> details) {
        audit.record(new Event(clock.instant(),m.requestId(),"MGT-BLOG-EDIT","OPERATION","ADMIN",actor,null,action,
                "BLOG_POST",target,"SUCCESS",null,m.ipAddress(),m.userAgent(),details));
    }
    private static String hash(Object value) {
        try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static String trim(String value) { if(value==null)return null; String v=value.trim(); return v.isEmpty()?null:v; }
    private static int len(String value) { return value==null?0:value.codePointCount(0,value.length()); }
}
