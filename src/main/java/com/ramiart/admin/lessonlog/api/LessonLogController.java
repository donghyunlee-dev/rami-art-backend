package com.ramiart.admin.lessonlog.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.lessonlog.application.LessonLogModels.AmendmentWrite;
import com.ramiart.admin.lessonlog.application.LessonLogModels.FinalizeWrite;
import com.ramiart.admin.lessonlog.application.LessonLogModels.LessonLogPage;
import com.ramiart.admin.lessonlog.application.LessonLogModels.RequestMetadata;
import com.ramiart.admin.lessonlog.application.LessonLogModels.SaveWrite;
import com.ramiart.admin.lessonlog.application.LessonLogModels.View;
import com.ramiart.admin.lessonlog.application.LessonLogService;
import com.ramiart.admin.lessonlog.application.LessonLogService.LessonLogException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/lesson-logs")
public final class LessonLogController {
    private static final Logger LOG=LoggerFactory.getLogger(LessonLogController.class);
    private final LessonLogService service;
    public LessonLogController(LessonLogService service) { this.service=service; }

    @GetMapping("")
    ResponseEntity<ApiEnvelope<LessonLogPage>> list(@RequestParam String month,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            Authentication auth,HttpServletRequest request) {
        return ok(service.list(month,keyword,status,page,size,auth),request);
    }

    @GetMapping("/session/{sessionId}")
    ResponseEntity<ApiEnvelope<View>> get(@PathVariable UUID sessionId,Authentication auth,HttpServletRequest request) {
        return ok(service.get(sessionId,auth),request);
    }

    @PostMapping("/session/{sessionId}/draft")
    ResponseEntity<ApiEnvelope<View>> createDraft(@PathVariable UUID sessionId,Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION,"/api/admin/lesson-logs/session/"+sessionId)
                .cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.createDraft(sessionId,auth,metadata(request)),RequestIdFilter.get(request)));
    }

    @PutMapping("/{logId}")
    ResponseEntity<ApiEnvelope<View>> save(@PathVariable UUID logId,@RequestBody SaveWrite body,Authentication auth,HttpServletRequest request) {
        return ok(service.save(logId,body,auth,metadata(request)),request);
    }

    @PostMapping("/{logId}/finalize")
    ResponseEntity<ApiEnvelope<View>> finalizeLog(@PathVariable UUID logId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody FinalizeWrite body,Authentication auth,HttpServletRequest request) {
        return ok(service.finalizeLog(logId,body,key,auth,metadata(request)),request);
    }

    @PostMapping("/{logId}/amendments")
    ResponseEntity<ApiEnvelope<View>> amend(@PathVariable UUID logId,@RequestBody AmendmentWrite body,Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.amend(logId,body,auth,metadata(request)),RequestIdFilter.get(request)));
    }

    @ExceptionHandler(LessonLogException.class)
    ResponseEntity<?> error(LessonLogException exception,HttpServletRequest request) {
        HttpStatus status=switch(exception.code()) {
            case "SESSION_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "LESSON_LOG_READ_DENIED","LESSON_LOG_WRITE_DENIED","LESSON_LOG_SCOPE_DENIED" -> HttpStatus.FORBIDDEN;
            case "LESSON_LOG_SESSION_NOT_FOUND","LESSON_LOG_NOT_FOUND","LESSON_LOG_PLAN_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LESSON_LOG_VERSION_CONFLICT","LESSON_LOG_FINALIZED","LESSON_LOG_DRAFT_EXISTS","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "LESSON_LOG_INCOMPLETE","LESSON_LOG_STUDENT_NOT_TARGET","LESSON_LOG_STUDENT_SET_INVALID",
                    "LESSON_LOG_ATTENDANCE_CHANGED","LESSON_LOG_MEDIA_NOT_READY","LESSON_LOG_SESSION_NOT_CLOSED",
                    "LESSON_LOG_ATTENDANCE_INCOMPLETE","LESSON_LOG_PLAN_MISMATCH","LESSON_LOG_CHANGE_REASON_REQUIRED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String,Object> error=new LinkedHashMap<>(); error.put("code",exception.code());
        error.put("message","수업 기록 요청을 처리할 수 없습니다.");
        if(!exception.details().isEmpty()) error.put("details",exception.details());
        Map<String,Object> response=new LinkedHashMap<>(); response.put("success",false);response.put("data",null);
        response.put("error",error);response.put("requestId",RequestIdFilter.get(request));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(response);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception,HttpServletRequest request) {
        LOG.error("Lesson log persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(request),request.getMethod(),request.getRequestURI());
        String code=switch(request.getMethod()) { case "GET"->"LESSON_LOG_READ_FAILED";case "POST"->"LESSON_LOG_WRITE_FAILED";default->"LESSON_LOG_SAVE_FAILED";};
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.failure(code,"수업 기록 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        String ua=request.getHeader(HttpHeaders.USER_AGENT); if(ua!=null&&ua.length()>512) ua=ua.substring(0,512);
        return new RequestMetadata(RequestIdFilter.get(request),request.getRemoteAddr(),ua);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(request)));
    }
}
