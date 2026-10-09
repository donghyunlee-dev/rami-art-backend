package com.ramiart.admin.consent.api;

import com.ramiart.admin.common.api.*;
import com.ramiart.admin.consent.application.ConsentEvidenceService;
import com.ramiart.admin.consent.application.ConsentEvidenceService.EvidenceAsset;
import com.ramiart.admin.consent.application.ConsentService.ConsentException;
import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaModels.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
public final class ConsentEvidenceController {
    private final ConsentEvidenceService service;
    public ConsentEvidenceController(ConsentEvidenceService service){this.service=service;}
    @PostMapping("/api/admin/students/{studentId}/consent-evidence-assets")
    public ResponseEntity<ApiEnvelope<EvidenceAsset>> upload(@PathVariable UUID studentId,@RequestPart("file")MultipartFile file,@RequestHeader("Idempotency-Key")UUID key,Authentication auth,HttpServletRequest r)throws IOException{
        if(file.getSize()>10L*1024*1024)throw new MediaException("MEDIA_FILE_TOO_LARGE");
        String agent=r.getHeader("User-Agent");if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");
        var meta=new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),agent==null?null:agent.substring(0,Math.min(512,agent.length())));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.upload(studentId,file.getBytes(),file.getOriginalFilename(),file.getContentType(),key,auth,meta),RequestIdFilter.get(r)));
    }
    @ExceptionHandler(ConsentException.class)
    public ResponseEntity<ApiEnvelope<Void>> error(ConsentException e,HttpServletRequest r){
        HttpStatus status=switch(e.code()){case "CONSENT_WRITE_DENIED"->HttpStatus.FORBIDDEN;case "STUDENT_NOT_FOUND","CONSENT_EVIDENCE_NOT_FOUND"->HttpStatus.NOT_FOUND;case "CONSENT_EVIDENCE_STORAGE_UNAVAILABLE"->HttpStatus.SERVICE_UNAVAILABLE;default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(e.code(),"동의 증빙을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));
    }
    @ExceptionHandler(MediaException.class)
    public ResponseEntity<ApiEnvelope<Void>> mediaError(MediaException e,HttpServletRequest r){
        HttpStatus status=switch(e.code()){case "MEDIA_FILE_TOO_LARGE"->HttpStatus.PAYLOAD_TOO_LARGE;case "MEDIA_SCAN_TIMEOUT"->HttpStatus.SERVICE_UNAVAILABLE;case "MEDIA_TYPE_NOT_SUPPORTED"->HttpStatus.UNSUPPORTED_MEDIA_TYPE;case "IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_REQUEST_PROCESSING"->HttpStatus.CONFLICT;case "MEDIA_FILE_CORRUPTED","MEDIA_DIMENSION_INVALID","MEDIA_SECURITY_REJECTED"->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(e.code(),"증빙 이미지를 확인해 주세요.",List.of(),RequestIdFilter.get(r)));
    }
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiEnvelope<Void>> missing(MissingServletRequestPartException e,HttpServletRequest r){return mediaError(new MediaException("MEDIA_FILE_REQUIRED"),r);}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiEnvelope<Void>> tooLarge(MaxUploadSizeExceededException e,HttpServletRequest r){return mediaError(new MediaException("MEDIA_FILE_TOO_LARGE"),r);}
}
