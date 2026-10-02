package com.ramiart.admin.schedule.api;

import static com.ramiart.admin.schedule.application.ScheduleModels.*;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.schedule.application.ScheduleException;
import com.ramiart.admin.schedule.application.ScheduleService;
import com.ramiart.admin.schedule.application.ScheduleService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/monthly-schedules")
public final class ScheduleController {
    private final ScheduleService service;
    public ScheduleController(ScheduleService service) { this.service = service; }

    @GetMapping("/{yearMonth}")
    ResponseEntity<ApiEnvelope<ScheduleView>> get(@PathVariable String yearMonth,
            @RequestParam(defaultValue = "EFFECTIVE") String mode, HttpServletRequest request) {
        return ok(service.get(yearMonth, mode), request);
    }

    @PostMapping("/{yearMonth}/drafts")
    ResponseEntity<ApiEnvelope<ScheduleView>> createDraft(@PathVariable String yearMonth,
            @RequestHeader("Idempotency-Key") UUID key, Authentication authentication, HttpServletRequest request) {
        ScheduleView value = service.createDraft(yearMonth, actor(authentication), key, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION,
                "/admin/monthly-schedules/" + yearMonth + "/drafts/" + value.id()).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }

    @PutMapping("/{yearMonth}/drafts/{draftId}")
    ResponseEntity<ApiEnvelope<ScheduleView>> save(@PathVariable String yearMonth, @PathVariable UUID draftId,
            @RequestBody DraftWrite body, Authentication authentication, HttpServletRequest request) {
        return ok(service.save(yearMonth, draftId, body, actor(authentication), metadata(request)), request);
    }

    @PostMapping("/{yearMonth}/publications")
    ResponseEntity<ApiEnvelope<Publication>> publish(@PathVariable String yearMonth,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody PublishWrite body,
            Authentication authentication, HttpServletRequest request) {
        Publication value = service.publish(yearMonth, body, actor(authentication), key, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }

    @ExceptionHandler(ScheduleException.class)
    ResponseEntity<?> error(ScheduleException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "MONTHLY_SCHEDULE_NOT_FOUND", "MONTHLY_SCHEDULE_DRAFT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "MONTHLY_SCHEDULE_DRAFT_EXISTS", "PUBLISHED_SCHEDULE_IMMUTABLE", "SCHEDULE_VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "SCHEDULE_CONFLICT", "SCHEDULE_NOT_PUBLISHABLE", "SCHEDULE_SLOT_CLASS_GROUP_MISMATCH", "SCHEDULE_ATTENDANCE_ALREADY_RECORDED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> error = new LinkedHashMap<>(); error.put("code", exception.code()); error.put("message", message(exception.code()));
        if (!exception.details().isEmpty()) error.put("details", exception.details());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("success", false, "error", error, "requestId", RequestIdFilter.get(request)));
    }

    private static String message(String code) { return switch (code) {
        case "MONTHLY_SCHEDULE_NOT_FOUND" -> "발행된 월간 시간표가 없습니다.";
        case "MONTHLY_SCHEDULE_DRAFT_NOT_FOUND" -> "월간 시간표 초안이 없습니다.";
        case "MONTHLY_SCHEDULE_DRAFT_EXISTS" -> "이미 편집 중인 초안이 있습니다.";
        case "SCHEDULE_VERSION_CONFLICT" -> "다른 관리자가 먼저 초안을 변경했습니다.";
        case "SCHEDULE_CONFLICT" -> "수업 시간 또는 예외 일정이 겹칩니다.";
        case "SCHEDULE_SLOT_CLASS_GROUP_MISMATCH" -> "수업 슬롯과 반 정보가 일치하지 않습니다.";
        case "SCHEDULE_ATTENDANCE_ALREADY_RECORDED" -> "이미 출석 결과가 기록된 일정은 변경할 수 없습니다.";
        case "YEAR_MONTH_INVALID" -> "조회 가능한 월 범위를 확인해 주세요.";
        default -> "시간표 입력값을 확인해 주세요.";
    }; }
    private static UUID actor(Authentication authentication) { return UUID.fromString(authentication.getName()); }
    private static RequestMetadata metadata(HttpServletRequest request) { String agent = request.getHeader(HttpHeaders.USER_AGENT); if (agent != null && agent.length() > 512) agent = agent.substring(0, 512); return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), agent); }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value, HttpServletRequest request) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value, RequestIdFilter.get(request))); }
}
