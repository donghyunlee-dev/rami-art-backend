package com.ramiart.admin.lessonplan.api;

import static com.ramiart.admin.lessonplan.application.LessonPlanModels.*;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.lessonplan.application.LessonPlanService;
import com.ramiart.admin.lessonplan.application.LessonPlanService.LessonPlanException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/lesson-plans")
public final class LessonPlanController {
    private static final Logger LOG = LoggerFactory.getLogger(LessonPlanController.class);
    private final LessonPlanService service;

    public LessonPlanController(LessonPlanService service) { this.service = service; }

    @GetMapping
    ResponseEntity<ApiEnvelope<PlanView>> get(@RequestParam String month, @RequestParam UUID classGroupId,
            Authentication authentication, HttpServletRequest request) {
        return ok(service.get(classGroupId, month, authentication), request);
    }

    @PostMapping("/draft")
    ResponseEntity<ApiEnvelope<PlanView>> createDraft(@RequestBody DraftCreate body, Authentication authentication,
            HttpServletRequest request) {
        PlanView view = service.createDraft(body.classGroupId(), body.month(), authentication, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION,
                "/api/admin/lesson-plans/draft/" + view.draft().id()).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(view, RequestIdFilter.get(request)));
    }

    @PutMapping("/draft/{id}")
    ResponseEntity<ApiEnvelope<PlanView>> save(@PathVariable UUID id, @RequestBody PlanWrite body,
            Authentication authentication, HttpServletRequest request) {
        return ok(service.save(id, body, authentication, metadata(request)), request);
    }

    @PostMapping("/preview")
    ResponseEntity<ApiEnvelope<PlanView>> preview(@RequestBody PreviewRequest body, Authentication authentication,
            HttpServletRequest request) {
        return ok(service.preview(body.classGroupId(), body.month(), body.draftId(), body.items(), authentication), request);
    }

    @PostMapping("/draft/{id}/publish")
    ResponseEntity<ApiEnvelope<PublishResult>> publish(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody PublishWrite body,
            Authentication authentication, HttpServletRequest request) {
        PublishResult result = service.publish(id, body, authentication, key, metadata(request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result, RequestIdFilter.get(request)));
    }

    @ExceptionHandler(LessonPlanException.class)
    ResponseEntity<?> error(LessonPlanException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "SESSION_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "LESSON_PLAN_READ_DENIED", "LESSON_PLAN_WRITE_DENIED", "LESSON_PLAN_PUBLISH_DENIED", "LESSON_PLAN_SCOPE_DENIED" -> HttpStatus.FORBIDDEN;
            case "LESSON_PLAN_CLASS_GROUP_NOT_FOUND", "LESSON_PLAN_DRAFT_NOT_FOUND", "LESSON_PLAN_SCHEDULE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LESSON_PLAN_DRAFT_EXISTS", "LESSON_PLAN_VERSION_CONFLICT", "LESSON_PLAN_SCHEDULE_CHANGED",
                    "LESSON_PLAN_PUBLISHED_IMMUTABLE", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "LESSON_PLAN_INCOMPLETE", "LESSON_PLAN_DATE_INVALID" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", exception.code());
        error.put("message", "수업 계획 요청을 처리할 수 없습니다.");
        if (!exception.details().isEmpty()) error.put("details", exception.details());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("data", null);
        envelope.put("error", error);
        envelope.put("requestId", RequestIdFilter.get(request));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(envelope);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception, HttpServletRequest request) {
        String root = exception.getMostSpecificCause() == null ? "" : exception.getMostSpecificCause().getMessage();
        if (root != null && root.contains("uq_lesson_plan_one_draft")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(
                    ApiEnvelope.failure("LESSON_PLAN_DRAFT_EXISTS", "이미 편집 중인 초안이 있습니다.", List.of(), RequestIdFilter.get(request)));
        }
        String code = switch (request.getMethod()) {
            case "GET" -> "LESSON_PLAN_READ_FAILED";
            case "POST" -> request.getRequestURI().endsWith("/publish") ? "LESSON_PLAN_PUBLISH_FAILED"
                    : request.getRequestURI().endsWith("/preview") ? "LESSON_PLAN_PREVIEW_FAILED" : "LESSON_PLAN_SAVE_FAILED";
            default -> "LESSON_PLAN_SAVE_FAILED";
        };
        LOG.error("Lesson plan persistence failed: requestId={}, method={}, path={}",
                RequestIdFilter.get(request), request.getMethod(), request.getRequestURI());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).body(
                ApiEnvelope.failure(code, "수업 계획 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }

    private static LessonPlanService.RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        return new LessonPlanService.RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }

    public record DraftCreate(UUID classGroupId, String month) {}
    public record PreviewRequest(UUID classGroupId, String month, UUID draftId, List<ItemWrite> items) {}
}
