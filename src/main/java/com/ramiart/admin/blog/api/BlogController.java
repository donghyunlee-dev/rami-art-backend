package com.ramiart.admin.blog.api;

import com.ramiart.admin.blog.application.BlogModels.*;
import com.ramiart.admin.blog.application.BlogService;
import com.ramiart.admin.blog.application.BlogService.RequestMetadata;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class BlogController {
    private final BlogService service;
    public BlogController(BlogService service) { this.service=service; }

    @GetMapping("/api/admin/blog-posts")
    ResponseEntity<ApiEnvelope<Page<Summary>>> adminList(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) List<String> categories, @RequestParam(required=false) List<String> states,
            @RequestParam(defaultValue="UPDATED_DESC") String sort, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,HttpServletRequest request) {
        return noStore(service.list(keyword,empty(categories),empty(states),sort,page,size),request);
    }
    @PostMapping("/api/admin/blog-posts")
    ResponseEntity<ApiEnvelope<Created>> create(@RequestBody(required=false) Write body,
            @RequestHeader("Idempotency-Key") UUID key, Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.create(body==null?new Write(null,null,null,null,null,null,false):body,
                        actor(auth),key,metadata(request)),RequestIdFilter.get(request)));
    }
    @GetMapping("/api/admin/blog-posts/{postId}")
    ResponseEntity<ApiEnvelope<Detail>> detail(@PathVariable UUID postId,@RequestParam(defaultValue="DRAFT") String mode,
            HttpServletRequest request) { return noStore(service.detail(postId,mode.toUpperCase()),request); }
    @PostMapping("/api/admin/blog-posts/{postId}/drafts")
    ResponseEntity<ApiEnvelope<Created>> createDraft(@PathVariable UUID postId,@RequestHeader("Idempotency-Key") UUID key,
            Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.createDraft(postId,actor(auth),key,metadata(request)),RequestIdFilter.get(request)));
    }
    @PutMapping("/api/admin/blog-posts/{postId}/drafts/{draftId}")
    ResponseEntity<ApiEnvelope<Saved>> save(@PathVariable UUID postId,@PathVariable UUID draftId,@RequestBody Save body,
            @RequestHeader("Idempotency-Key") UUID key,Authentication auth,HttpServletRequest request) {
        return noStore(service.save(postId,draftId,body,actor(auth),key,metadata(request)),request);
    }
    @GetMapping("/api/admin/blog-posts/{postId}/drafts/{draftId}/preview")
    ResponseEntity<ApiEnvelope<Detail>> preview(@PathVariable UUID postId,@PathVariable UUID draftId,
            @RequestParam long version,HttpServletRequest request) { return noStore(service.preview(postId,draftId,version),request); }
    @PostMapping("/api/admin/blog-posts/{postId}/publications")
    ResponseEntity<ApiEnvelope<Publication>> publish(@PathVariable UUID postId,@RequestBody Publish body,
            @RequestHeader("Idempotency-Key") UUID key,Authentication auth,HttpServletRequest request) {
        return noStore(service.publish(postId,body,actor(auth),key,metadata(request)),request);
    }
    @GetMapping("/api/public/blog-posts")
    ResponseEntity<ApiEnvelope<Page<PublicItem>>> publicList(@RequestParam(required=false) String category,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="12") int size,HttpServletRequest request) {
        return ResponseEntity.ok().body(ApiEnvelope.success(service.publicList(category,keyword,page,size),RequestIdFilter.get(request)));
    }
    private static UUID actor(Authentication auth) { return UUID.fromString(auth.getName()); }
    private static List<String> empty(List<String> values) { return values==null?List.of():values; }
    private static RequestMetadata metadata(HttpServletRequest request) {
        String agent=request.getHeader(HttpHeaders.USER_AGENT);
        if(agent!=null) agent=agent.replaceAll("[\\p{Cntrl}]","");
        if(agent!=null&&agent.length()>512) agent=agent.substring(0,512);
        return new RequestMetadata(RequestIdFilter.get(request),request.getRemoteAddr(),agent);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> noStore(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));
    }
}
