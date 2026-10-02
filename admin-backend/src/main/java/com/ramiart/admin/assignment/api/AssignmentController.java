package com.ramiart.admin.assignment.api;

import static com.ramiart.admin.assignment.application.AssignmentModels.*;

import com.ramiart.admin.assignment.application.AssignmentException;
import com.ramiart.admin.assignment.application.AssignmentService;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public final class AssignmentController {
    private final AssignmentService service;
    public AssignmentController(AssignmentService service) { this.service = service; }

    @GetMapping("/students/{studentId}/schedule-assignments")
    ResponseEntity<ApiEnvelope<AssignmentPage>> list(@PathVariable UUID studentId,
            @RequestParam(defaultValue = "true") boolean includeEnded, HttpServletRequest request) {
        return ok(service.list(studentId, includeEnded), request);
    }

    @GetMapping("/students/{studentId}/schedule-assignment-candidates")
    ResponseEntity<ApiEnvelope<Map<String, List<CandidateView>>>> candidates(@PathVariable UUID studentId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpServletRequest request) {
        return ok(Map.of("items", service.candidates(studentId, from, to)), request);
    }

    @PostMapping("/students/{studentId}/schedule-assignments")
    ResponseEntity<ApiEnvelope<AssignmentView>> create(@PathVariable UUID studentId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody CreateCommand body,
            Authentication authentication, HttpServletRequest request) {
        AssignmentView value = service.create(studentId, body, actor(authentication), key, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION,
                "/admin/student-schedule-assignments/" + value.id()).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }

    @PutMapping("/student-schedule-assignments/{id}")
    ResponseEntity<ApiEnvelope<AssignmentView>> update(@PathVariable UUID id, @RequestBody UpdateCommand body,
            Authentication authentication, HttpServletRequest request) {
        return ok(service.update(id, body, actor(authentication), metadata(request)), request);
    }

    @DeleteMapping("/student-schedule-assignments/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version,
            Authentication authentication, HttpServletRequest request) {
        service.deleteFuture(id, version, actor(authentication), metadata(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(AssignmentException.class)
    ResponseEntity<?> error(AssignmentException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "STUDENT_NOT_FOUND", "SCHEDULE_SLOT_NOT_FOUND", "ASSIGNMENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "STUDENT_SCHEDULE_CONFLICT", "ASSIGNMENT_VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "ASSIGNMENT_HISTORY_EXISTS", "STUDENT_STATUS_NOT_ASSIGNABLE", "SCHEDULE_SLOT_RETIRED" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", exception.code());
        error.put("message", message(exception.code()));
        if (!exception.conflicts().isEmpty()) error.put("details", Map.of("conflicts", exception.conflicts()));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("success", false, "error", error, "requestId", RequestIdFilter.get(request)));
    }

    private static String message(String code) {
        return switch (code) {
            case "STUDENT_NOT_FOUND" -> "원생을 찾을 수 없습니다.";
            case "SCHEDULE_SLOT_NOT_FOUND" -> "배정 가능한 발행 수업을 찾을 수 없습니다.";
            case "ASSIGNMENT_NOT_FOUND" -> "수업 배정을 찾을 수 없습니다.";
            case "STUDENT_SCHEDULE_CONFLICT" -> "기존 수업 배정과 시간이 겹칩니다.";
            case "ASSIGNMENT_VERSION_CONFLICT" -> "다른 관리자가 먼저 배정을 변경했습니다.";
            case "ASSIGNMENT_HISTORY_EXISTS" -> "출석 이력이 있는 기간은 제외하거나 삭제할 수 없습니다.";
            case "STUDENT_STATUS_NOT_ASSIGNABLE" -> "재원 상태의 원생만 새 수업을 배정할 수 있습니다.";
            default -> "수업 배정 입력값을 확인해 주세요.";
        };
    }

    private static UUID actor(Authentication authentication) { return UUID.fromString(authentication.getName()); }
    private static RequestMetadata metadata(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent != null && agent.length() > 512) agent = agent.substring(0, 512);
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), agent);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }
}
