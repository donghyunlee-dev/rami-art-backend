package com.ramiart.admin.homecontent.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.homecontent.application.HomePageContentModels.*;
import com.ramiart.admin.homecontent.application.HomePageContentService;
import com.ramiart.admin.homecontent.application.HomePageContentService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class HomePageContentController {
    private final HomePageContentService service;
    public HomePageContentController(HomePageContentService service) { this.service = service; }

    @GetMapping("/api/admin/home-page-content")
    ResponseEntity<ApiEnvelope<AdminView>> get(Authentication auth, HttpServletRequest request) {
        return admin(service.get(auth), request);
    }

    @GetMapping("/api/admin/home-page-content/options")
    ResponseEntity<ApiEnvelope<Options>> options(Authentication auth, HttpServletRequest request) {
        return admin(service.options(auth), request);
    }

    @GetMapping("/api/admin/home-page-content/revisions/{revisionId}")
    ResponseEntity<ApiEnvelope<RevisionView>> revision(@PathVariable UUID revisionId, Authentication auth, HttpServletRequest request) {
        return admin(service.revision(revisionId, auth), request);
    }

    @PostMapping("/api/admin/home-page-content/drafts")
    ResponseEntity<ApiEnvelope<RevisionView>> create(@RequestParam(required = false) UUID sourceRevisionId,
            @RequestHeader("Idempotency-Key") UUID key, Authentication auth, HttpServletRequest request) {
        RevisionView draft = service.createDraft(sourceRevisionId, actor(auth), key, metadata(request));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(draft, RequestIdFilter.get(request)));
    }

    @PutMapping("/api/admin/home-page-content/drafts/{draftId}")
    ResponseEntity<ApiEnvelope<RevisionView>> update(@PathVariable UUID draftId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody HomePageWrite body, Authentication auth, HttpServletRequest request) {
        return admin(service.updateDraft(draftId, body, actor(auth), key, metadata(request)), request);
    }

    @PostMapping("/api/admin/home-page-content/drafts/{draftId}/preview")
    ResponseEntity<ApiEnvelope<Preview>> preview(@PathVariable UUID draftId, @RequestBody PreviewWrite body,
            Authentication auth, HttpServletRequest request) {
        return admin(service.preview(draftId, body.version(), auth), request);
    }

    @PostMapping("/api/admin/home-page-content/publications")
    ResponseEntity<ApiEnvelope<Publication>> publish(@RequestBody PublishWrite body,
            @RequestHeader("Idempotency-Key") UUID key, Authentication auth, HttpServletRequest request) {
        Publication publication = service.publish(body.draftId(), body.version(), actor(auth), key, metadata(request));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(publication, RequestIdFilter.get(request)));
    }

    @GetMapping("/api/public/home-page-content")
    ResponseEntity<ApiEnvelope<PublicView>> publicContent(HttpServletRequest request) {
        PublicView content = service.publicContent();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(60, java.util.concurrent.TimeUnit.SECONDS).cachePublic()
                        .staleWhileRevalidate(java.time.Duration.ofSeconds(300)))
                .eTag("\"home-page-r" + content.revision() + "\"")
                .body(ApiEnvelope.success(content, RequestIdFilter.get(request)));
    }

    public record PreviewWrite(long version) {}
    public record PublishWrite(UUID draftId, long version) {}
    private static UUID actor(Authentication auth) { return UUID.fromString(auth.getName()); }
    private static RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) { userAgent = userAgent.replaceAll("[\\p{Cntrl}]", ""); if (userAgent.length() > 512) userAgent = userAgent.substring(0, 512); }
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> admin(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }
}
