package com.ramiart.admin.classprogram.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.classprogram.application.ClassProgramException;
import com.ramiart.admin.classprogram.application.ClassProgramService;
import com.ramiart.admin.classprogram.application.ClassProgramService.RequestMetadata;
import static com.ramiart.admin.classprogram.application.ClassProgramModels.*;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ClassProgramController {
    private final ClassProgramService service; private final ObjectMapper mapper;
    public ClassProgramController(ClassProgramService service,ObjectMapper mapper){this.service=service;this.mapper=mapper;}
    @GetMapping("/api/admin/content/class-programs") ResponseEntity<ApiEnvelope<ProgramList>> list(Authentication a,HttpServletRequest r){return admin(service.list(a),r);}
    @PostMapping("/api/admin/content/class-programs/{courseId}/drafts") ResponseEntity<ApiEnvelope<ProgramItem>> create(@PathVariable UUID courseId,@RequestHeader("Idempotency-Key") UUID key,Authentication a,HttpServletRequest r){var x=service.create(courseId,key,a,meta(r));return ResponseEntity.status(x.replay()?200:201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(x.data(),RequestIdFilter.get(r)));}
    @PutMapping("/api/admin/content/class-programs/{courseId}/drafts/{draftId}") ResponseEntity<ApiEnvelope<ProgramItem>> save(@PathVariable UUID courseId,@PathVariable UUID draftId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JsonNode raw,Authentication a,HttpServletRequest r){
        keys(raw,Set.of("version","audienceLabel","title","description","activities","mediaAssetId","altText","visible","displayOrder"));ProgramWrite w=convert(raw,ProgramWrite.class);return admin(service.save(courseId,draftId,w,key,a,meta(r)),r);}
    @PostMapping("/api/admin/content/class-programs/preview") ResponseEntity<ApiEnvelope<Preview>> preview(Authentication a,HttpServletRequest r){return admin(service.preview(a),r);}
    @PostMapping("/api/admin/content/class-programs/{courseId}/publications") ResponseEntity<ApiEnvelope<Publication>> publish(@PathVariable UUID courseId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody JsonNode raw,Authentication a,HttpServletRequest r){keys(raw,Set.of("draftId","version","changeSummary"));PublishRequest p=convert(raw,PublishRequest.class);var x=service.publish(p,key,a,meta(r));if(!x.data().courseId().equals(courseId))throw new ClassProgramException("CLASS_PROGRAM_COURSE_NOT_FOUND");return ResponseEntity.status(x.replay()?200:201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(x.data(),RequestIdFilter.get(r)));}
    @GetMapping("/api/public/class-programs") ResponseEntity<ApiEnvelope<java.util.List<PublicProgram>>> publicList(HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic().staleWhileRevalidate(java.time.Duration.ofSeconds(300))).body(ApiEnvelope.success(service.publicPrograms(),RequestIdFilter.get(r)));}
    private <T>ResponseEntity<ApiEnvelope<T>> admin(T d,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(d,RequestIdFilter.get(r)));}
    private <T>T convert(JsonNode n,Class<T> c){try{return mapper.treeToValue(n,c);}catch(Exception e){throw new ClassProgramException("VALIDATION_ERROR");}}
    private void keys(JsonNode n,Set<String> allowed){if(n==null||!n.isObject())throw new ClassProgramException("VALIDATION_ERROR");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new ClassProgramException("VALIDATION_ERROR",k);});}
    private RequestMetadata meta(HttpServletRequest r){String ua=r.getHeader(HttpHeaders.USER_AGENT);if(ua!=null){ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua.length()>512)ua=ua.substring(0,512);}return new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),ua);}
}
