package com.ramiart.admin.sitebrand.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.sitebrand.application.SiteBrandModels.*;
import com.ramiart.admin.sitebrand.application.SiteBrandService;
import com.ramiart.admin.sitebrand.application.SiteBrandService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class SiteBrandController {
    private final SiteBrandService service;
    public SiteBrandController(SiteBrandService service) { this.service=service; }
    @GetMapping("/api/admin/site-brand") ResponseEntity<ApiEnvelope<AdminView>> get(HttpServletRequest request) {
        return admin(service.get(),request);
    }
    @PostMapping("/api/admin/site-brand/draft") ResponseEntity<ApiEnvelope<RevisionView>> create(
            @RequestHeader("Idempotency-Key") UUID key, Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(
                service.createDraft(actor(auth),key,meta(request)),RequestIdFilter.get(request)));
    }
    @PutMapping("/api/admin/site-brand/draft/{draftId}") ResponseEntity<ApiEnvelope<RevisionView>> update(
            @PathVariable UUID draftId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody BrandWrite body,
            Authentication auth,HttpServletRequest request) {
        return admin(service.updateDraft(draftId,body,actor(auth),key,meta(request)),request);
    }
    @PostMapping("/api/admin/site-brand/preview") ResponseEntity<ApiEnvelope<Preview>> preview(@RequestBody BrandWrite body,HttpServletRequest request) {
        return admin(service.preview(body),request);
    }
    @PostMapping("/api/admin/site-brand/draft/{draftId}/publish") ResponseEntity<ApiEnvelope<RevisionView>> publish(
            @PathVariable UUID draftId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody PublishWrite body,
            Authentication auth,HttpServletRequest request) {
        return admin(service.publish(draftId,body.version(),actor(auth),key,meta(request)),request);
    }
    @GetMapping("/api/public/site-brand") ResponseEntity<ApiEnvelope<PublicBrand>> publicBrand(HttpServletRequest request) {
        PublicBrand brand=service.publicBrand();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(60,java.util.concurrent.TimeUnit.SECONDS).cachePublic()
                        .staleWhileRevalidate(java.time.Duration.ofSeconds(300)))
                .eTag("\"site-brand-"+brand.revision()+"\"").body(ApiEnvelope.success(brand,RequestIdFilter.get(request)));
    }
    public record PublishWrite(long version) {}
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static RequestMetadata meta(HttpServletRequest r) { String ua=r.getHeader(HttpHeaders.USER_AGENT); if(ua!=null){ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua.length()>512)ua=ua.substring(0,512);} return new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),ua); }
    private static <T> ResponseEntity<ApiEnvelope<T>> admin(T data,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(r)));}
}
