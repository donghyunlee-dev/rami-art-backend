package com.ramiart.admin.notification.api;

import static com.ramiart.admin.notification.application.NotificationModels.*;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.notification.application.NotificationException;
import com.ramiart.admin.notification.application.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notifications")
public final class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service){this.service=service;}

    @PostMapping("/preview")
    ResponseEntity<ApiEnvelope<Preview>> preview(@RequestBody DraftRequest body,Authentication auth,HttpServletRequest request){return ok(service.preview(body,auth),request);}
    @PostMapping
    ResponseEntity<ApiEnvelope<QueueResult>> queue(@RequestBody QueueRequest body,@RequestHeader("Idempotency-Key")UUID key,Authentication auth,HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.queue(body,key,auth,metadata(request)),RequestIdFilter.get(request)));
    }
    @GetMapping
    ResponseEntity<ApiEnvelope<MessagePage>> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,
            @RequestParam(required=false)String status,@RequestParam(required=false)String type,@RequestParam(required=false)String channel,
            Authentication auth,HttpServletRequest request){return ok(service.list(page,size,status,type,channel,auth),request);}
    @GetMapping("/{id}")
    ResponseEntity<ApiEnvelope<MessageDetail>> detail(@PathVariable UUID id,Authentication auth,HttpServletRequest request){return ok(service.detail(id,auth),request);}
    @PostMapping("/{id}/cancellation")
    ResponseEntity<ApiEnvelope<MessageDetail>> cancel(@PathVariable UUID id,@RequestBody CancelRequest body,Authentication auth,HttpServletRequest request){return ok(service.cancel(id,body==null?-1:body.version(),body==null?null:body.reason(),auth,metadata(request)),request);}
    @PostMapping("/{id}/retry")
    ResponseEntity<ApiEnvelope<MessageDetail>> retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key")UUID key,Authentication auth,HttpServletRequest request){return ok(service.retry(id,key,auth,metadata(request)),request);}

    private static <T>ResponseEntity<ApiEnvelope<T>>ok(T value,HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(request)));}
    private static RequestMetadata metadata(HttpServletRequest r){String agent=r.getHeader(HttpHeaders.USER_AGENT);if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);return new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),agent);}
    @ExceptionHandler(NotificationException.class)
    ResponseEntity<ApiEnvelope<Void>> error(NotificationException exception,HttpServletRequest request){
        String code=exception.code();HttpStatus status=switch(code){
            case "NOTIFICATION_READ_DENIED","NOTIFICATION_SEND_DENIED"->HttpStatus.FORBIDDEN;
            case "NOTIFICATION_NOT_FOUND"->HttpStatus.NOT_FOUND;
            case "NOTIFICATION_PREVIEW_STALE","NOTIFICATION_VERSION_CONFLICT","NOTIFICATION_NOT_RETRYABLE","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;
            case "NOTIFICATION_CHANNEL_NOT_CONFIGURED","NOTIFICATION_NO_ELIGIBLE_RECIPIENT"->HttpStatus.UNPROCESSABLE_ENTITY;
            default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,"안내 발송 정보를 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
    public record CancelRequest(long version,String reason){}
}
