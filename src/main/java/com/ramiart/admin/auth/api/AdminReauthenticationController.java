package com.ramiart.admin.auth.api;

import com.ramiart.admin.auth.application.AdminReauthenticationService;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth/reauthentication")
public class AdminReauthenticationController {
    public static final String COOKIE_NAME=AuthSessionController.COOKIE_NAME;
    private final AdminReauthenticationService service;
    public AdminReauthenticationController(AdminReauthenticationService service){this.service=service;}
    @PostMapping
    ResponseEntity<ApiEnvelope<AdminReauthenticationService.Issued>> issue(@CookieValue(name=COOKIE_NAME,required=false) String session,
            @Valid @RequestBody IssueRequest body,Authentication authentication,HttpServletRequest request){
        String ua=request.getHeader(HttpHeaders.USER_AGENT);if(ua!=null)ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua!=null&&ua.length()>512)ua=ua.substring(0,512);
        var issued=service.issue(session,body.password(),body.purpose(),authentication,
                new AdminReauthenticationService.Metadata(RequestIdFilter.get(request),request.getRemoteAddr(),ua));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(issued,RequestIdFilter.get(request)));
    }
    @ExceptionHandler(AdminReauthenticationService.ReauthenticationException.class)
    ResponseEntity<ApiEnvelope<Void>> error(AdminReauthenticationService.ReauthenticationException e,HttpServletRequest request){
        int status=switch(e.code()){case "SESSION_REQUIRED"->401;case "TOO_MANY_REQUESTS"->429;case "VALIDATION_ERROR"->400;default->403;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(e.code(),"재인증을 완료하지 못했습니다.",java.util.List.of(),RequestIdFilter.get(request)));
    }
    public record IssueRequest(@NotBlank @Size(min=8,max=128) String password,
            @NotBlank @Pattern(regexp="MAKEUP_EXTENSION|RETENTION_EXECUTION|GALLERY_PUBLISH_EXEMPTION") String purpose){}
}
