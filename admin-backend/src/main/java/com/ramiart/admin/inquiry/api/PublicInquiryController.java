package com.ramiart.admin.inquiry.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.inquiry.application.InquiryModels.Accepted;
import com.ramiart.admin.inquiry.application.InquiryModels.PublicSubmission;
import com.ramiart.admin.inquiry.application.InquiryService;
import com.ramiart.admin.inquiry.application.InquiryService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/inquiries")
public final class PublicInquiryController {
    private final InquiryService service;
    public PublicInquiryController(InquiryService service){this.service=service;}

    @PostMapping
    ResponseEntity<ApiEnvelope<Accepted>> submit(@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody PublicSubmission body,HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.submit(body,key,metadata(request)),RequestIdFilter.get(request)));
    }

    static RequestMetadata metadata(HttpServletRequest request){
        String userAgent=request.getHeader("User-Agent");
        if(userAgent!=null)userAgent=userAgent.replaceAll("[\\p{Cntrl}]","");
        if(userAgent!=null&&userAgent.length()>512)userAgent=userAgent.substring(0,512);
        return new RequestMetadata(RequestIdFilter.get(request),request.getRemoteAddr(),userAgent);
    }
}
