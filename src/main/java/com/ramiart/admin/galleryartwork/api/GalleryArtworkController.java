package com.ramiart.admin.galleryartwork.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.galleryartwork.application.GalleryArtworkException;
import com.ramiart.admin.galleryartwork.application.GalleryArtworkService;
import com.ramiart.admin.galleryartwork.application.GalleryArtworkService.RequestMetadata;
import static com.ramiart.admin.galleryartwork.application.GalleryArtworkModels.*;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.auth.api.AuthSessionController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class GalleryArtworkController {
    private static final Set<String> CONTENT=Set.of("title","courseId","audienceLabel","medium","description","mediaAssetId","altText","studentConsentId","consentExemptionReason","visible","featured","featuredOrder");
    private final GalleryArtworkService service;private final ObjectMapper mapper;
    public GalleryArtworkController(GalleryArtworkService service,ObjectMapper mapper){this.service=service;this.mapper=mapper;}
    @GetMapping("/api/admin/gallery-artworks") ResponseEntity<ApiEnvelope<Page>> list(@RequestParam(required=false)String keyword,@RequestParam(required=false)List<UUID> courseIds,@RequestParam(required=false)List<String> states,@RequestParam(defaultValue="ALL")String featured,@RequestParam(defaultValue="UPDATED_DESC")String sort,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,Authentication a,HttpServletRequest r){return admin(service.list(keyword,courseIds,states,featured,sort,page,size,a),r);}
    @GetMapping("/api/admin/gallery-artworks/{artworkId}") ResponseEntity<ApiEnvelope<Detail>> detail(@PathVariable UUID artworkId,@RequestParam(defaultValue="draft")String mode,Authentication a,HttpServletRequest r){return admin(service.detail(artworkId,mode,a),r);}
    @PostMapping("/api/admin/gallery-artworks") ResponseEntity<ApiEnvelope<Detail>> create(@RequestHeader("Idempotency-Key")UUID key,@RequestBody JsonNode raw,Authentication a,HttpServletRequest r){Content c=convert(raw,CONTENT,Content.class);var x=service.create(c,key,a,meta(r));return write(x.replay()?200:201,x.data(),r);}
    @PostMapping("/api/admin/gallery-artworks/{artworkId}/drafts") ResponseEntity<ApiEnvelope<Detail>> createDraft(@PathVariable UUID artworkId,@RequestHeader("Idempotency-Key")UUID key,Authentication a,HttpServletRequest r){var x=service.createDraft(artworkId,key,a,meta(r));return write(x.replay()?200:201,x.data(),r);}
    @PutMapping("/api/admin/gallery-artworks/{artworkId}/drafts/{draftId}") ResponseEntity<ApiEnvelope<Detail>> save(@PathVariable UUID artworkId,@PathVariable UUID draftId,@RequestBody JsonNode raw,Authentication a,HttpServletRequest r){Set<String> allowed=new HashSet<>(CONTENT);allowed.add("version");validateKeys(raw,allowed);return admin(service.save(artworkId,draftId,convert(raw,allowed,Write.class),a,meta(r)),r);}
    @PostMapping("/api/admin/gallery-artworks/preview") ResponseEntity<ApiEnvelope<Preview>> preview(@RequestBody JsonNode raw,Authentication a,HttpServletRequest r){keys(raw,Set.of("artworkId","draftId","draftVersion"));try{return admin(service.preview(UUID.fromString(raw.path("artworkId").asText()),UUID.fromString(raw.path("draftId").asText()),raw.path("draftVersion").asLong(-1),a),r);}catch(IllegalArgumentException e){throw new GalleryArtworkException("VALIDATION_ERROR");}}
    @PostMapping("/api/admin/gallery-artworks/{artworkId}/publications") ResponseEntity<ApiEnvelope<Detail>> publish(@PathVariable UUID artworkId,@RequestHeader("Idempotency-Key")UUID key,@RequestBody JsonNode raw,Authentication a,HttpServletRequest r){keys(raw,Set.of("draftId","draftVersion","consentExemptionReason","reauthToken"));PublicationRequest p=convert(raw,Set.of("draftId","draftVersion","consentExemptionReason","reauthToken"),PublicationRequest.class);String cookie=r.getCookies()==null?null:Arrays.stream(r.getCookies()).filter(c->AuthSessionController.COOKIE_NAME.equals(c.getName())).map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);var x=service.publish(artworkId,p,key,cookie,a,meta(r));return write(x.replay()?200:201,x.data(),r);}
    @GetMapping("/api/admin/student-consents") ResponseEntity<ApiEnvelope<ConsentPage>> consents(@RequestParam(required=false)String keyword,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="10")int size,Authentication a,HttpServletRequest r){return admin(service.consents(keyword,page,size,a),r);}
    @GetMapping("/api/public/gallery-artworks") ResponseEntity<ApiEnvelope<PublicPage>> publicList(@RequestParam(required=false)List<String> courseCodes,@RequestParam(required=false)Boolean featured,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="24")int size,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic().staleWhileRevalidate(Duration.ofSeconds(300))).body(ApiEnvelope.success(service.publicList(courseCodes,featured,page,size),RequestIdFilter.get(r)));}
    private <T>ResponseEntity<ApiEnvelope<T>> admin(T value,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(r)));}
    private <T>ResponseEntity<ApiEnvelope<T>> write(int status,T value,HttpServletRequest r){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(r)));}
    private <T>T convert(JsonNode n,Set<String> fields,Class<T> type){keys(n,fields);try{return mapper.treeToValue(n,type);}catch(Exception e){throw new GalleryArtworkException("VALIDATION_ERROR");}}
    private void keys(JsonNode n,Set<String> fields){if(n==null||!n.isObject())throw new GalleryArtworkException("VALIDATION_ERROR");validateKeys(n,fields);}
    private void validateKeys(JsonNode n,Set<String> fields){n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw new GalleryArtworkException("VALIDATION_ERROR",k);});}
    private RequestMetadata meta(HttpServletRequest r){String ua=r.getHeader(HttpHeaders.USER_AGENT);if(ua!=null){ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua.length()>512)ua=ua.substring(0,512);}return new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),ua);}
}
