package com.ramiart.admin.retention.api;

import static com.ramiart.admin.retention.application.RetentionModels.*;
import com.ramiart.admin.auth.api.AuthSessionController;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.retention.application.RetentionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/retention")
public final class RetentionController {
    private final RetentionService service;
    public RetentionController(RetentionService service){this.service=service;}
    @GetMapping("/policies") public ResponseEntity<ApiEnvelope<Policies>> policies(Authentication a,HttpServletRequest r){return ok(service.policies(a),r);}
    @GetMapping("/holds") public ResponseEntity<ApiEnvelope<HoldPage>> holds(@RequestParam(required=false)String targetType,@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="50")int size,Authentication a,HttpServletRequest r){return ok(service.holds(targetType,status,cursor,size,a),r);}
    @PostMapping("/holds") public ResponseEntity<ApiEnvelope<Hold>> createHold(@RequestBody HoldWrite b,Authentication a,HttpServletRequest r){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.createHold(b,a,meta(r)),RequestIdFilter.get(r)));}
    @PostMapping("/holds/{id}/release") public ResponseEntity<ApiEnvelope<Hold>> release(@PathVariable UUID id,@RequestBody Release b,Authentication a,HttpServletRequest r){return ok(service.releaseHold(id,b.reason(),a,meta(r)),r);}
    @PostMapping("/previews") public ResponseEntity<ApiEnvelope<Preview>> preview(@RequestBody PreviewWrite b,Authentication a,HttpServletRequest r){return ok(service.preview(b,a),r);}
    @PostMapping("/runs") public ResponseEntity<ApiEnvelope<Run>> execute(@RequestBody RunWrite b,@RequestHeader("Idempotency-Key")UUID key,Authentication a,HttpServletRequest r){
        String cookie=r.getCookies()==null?null:Arrays.stream(r.getCookies()).filter(c->AuthSessionController.COOKIE_NAME.equals(c.getName())).map(jakarta.servlet.http.Cookie::getValue).findFirst().orElse(null);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.execute(b,key,cookie,a,meta(r)),RequestIdFilter.get(r)));
    }
    @GetMapping("/runs/{id}") public ResponseEntity<ApiEnvelope<Run>> run(@PathVariable UUID id,Authentication a,HttpServletRequest r){return ok(service.run(id,a),r);}
    @ExceptionHandler(RetentionException.class) public ResponseEntity<ApiEnvelope<Void>> error(RetentionException e,HttpServletRequest r){
        String code=e.getMessage();HttpStatus status=switch(code){
            case "SESSION_REQUIRED"->HttpStatus.UNAUTHORIZED;
            case "RETENTION_READ_DENIED","RETENTION_EXECUTE_DENIED","RETENTION_OWNER_REAUTH_REQUIRED"->HttpStatus.FORBIDDEN;
            case "RETENTION_TARGET_NOT_FOUND","RETENTION_RUN_NOT_FOUND","RETENTION_PREVIEW_NOT_FOUND"->HttpStatus.NOT_FOUND;
            case "RETENTION_HOLD_EXISTS","RETENTION_HOLD_NOT_ACTIVE","RETENTION_PREVIEW_STALE","RETENTION_RUN_IN_PROGRESS","IDEMPOTENCY_KEY_REUSED"->HttpStatus.CONFLICT;
            case "RETENTION_CONFIRMATION_INVALID"->HttpStatus.UNPROCESSABLE_ENTITY;
            default->HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,"보존·파기 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));
    }
    public record Release(String reason){}
    private static Metadata meta(HttpServletRequest r){String agent=r.getHeader("User-Agent");if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");return new Metadata(RequestIdFilter.get(r),r.getRemoteAddr(),agent==null?null:agent.substring(0,Math.min(agent.length(),512)));}
    private static <T>ResponseEntity<ApiEnvelope<T>> ok(T value,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(r)));}
}
