package com.ramiart.admin.inquiry.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.inquiry.application.InquiryModels.*;
import com.ramiart.admin.inquiry.application.InquiryService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/inquiries")
public final class AdminInquiryController {
    private final InquiryService service;
    public AdminInquiryController(InquiryService service){this.service=service;}

    @GetMapping("/options") ResponseEntity<ApiEnvelope<List<CourseOption>>> options(HttpServletRequest request){
        return ok(service.courseOptions(),request);
    }

    @GetMapping ResponseEntity<ApiEnvelope<InquiryPage>> list(@RequestParam(required=false)String keyword,
            @RequestParam(required=false)List<UUID> courseIds,@RequestParam(required=false)List<String> statuses,
            @RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to,
            @RequestParam(defaultValue="ALL")String readState,@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="20")int size,HttpServletRequest request){
        return ok(service.list(keyword,courseIds,statuses,from,to,readState,page,size),request);
    }
    @GetMapping("/{id}") ResponseEntity<ApiEnvelope<InquiryDetail>> detail(@PathVariable UUID id,HttpServletRequest request){return ok(service.detail(id),request);}
    @PostMapping("/{id}/read-receipts") ResponseEntity<ApiEnvelope<ReadReceipt>> read(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody ReadReceiptWrite body,Authentication auth,HttpServletRequest request){
        return ok(service.markRead(id,body,actor(auth),key,PublicInquiryController.metadata(request)),request);
    }
    @PostMapping("/{id}/activities") ResponseEntity<ApiEnvelope<ActivityCreated>> activity(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody ActivityWrite body,Authentication auth,HttpServletRequest request){
        ActivityCreated created=service.addActivity(id,body,actor(auth),key,PublicInquiryController.metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(created,RequestIdFilter.get(request)));
    }
    private static UUID actor(Authentication auth){return UUID.fromString(auth.getName());}
    private static <T>ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request){return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}
}
