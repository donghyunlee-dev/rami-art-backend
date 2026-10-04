package com.ramiart.admin.attendance.api;

import static com.ramiart.admin.attendance.application.AttendanceModels.*;
import com.ramiart.admin.attendance.application.AttendanceService.RequestMetadata;
import com.ramiart.admin.attendance.application.AttendanceService;
import com.ramiart.admin.attendance.application.AttendanceService.AttendanceException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/attendance-sessions")
public final class AttendanceController {
    private final AttendanceService service;
    public AttendanceController(AttendanceService service) { this.service = service; }
    @GetMapping
    ResponseEntity<ApiEnvelope<Day>> get(@RequestParam(required = false) LocalDate date, @RequestParam(defaultValue = "ALL") String attendanceStatus,
                                         Authentication authentication, HttpServletRequest request) {
        LocalDate selected = date == null ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : date;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.find(selected, attendanceStatus, authentication), RequestIdFilter.get(request)));
    }
    @GetMapping("/{sessionId}")
    ResponseEntity<ApiEnvelope<Session>> detail(@PathVariable UUID sessionId, Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.findById(sessionId, authentication), RequestIdFilter.get(request)));
    }
    @PutMapping("/{sessionId}/students/{studentId}")
    ResponseEntity<ApiEnvelope<SavedAttendance>> save(@PathVariable UUID sessionId, @PathVariable UUID studentId,
            @RequestBody AttendanceWrite body, Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(
                service.save(sessionId, studentId, body, authentication, metadata(request)), RequestIdFilter.get(request)));
    }
    @PostMapping("/{sessionId}/closures")
    ResponseEntity<ApiEnvelope<ClosedSession>> close(@PathVariable UUID sessionId, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CloseWrite body, Authentication authentication, HttpServletRequest request) {
        CloseResult result = service.close(sessionId, body, authentication, key, metadata(request));
        int status = result.created() ? 201 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.session(), RequestIdFilter.get(request)));
    }
    private static RequestMetadata metadata(HttpServletRequest request) {
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
    @ExceptionHandler(AttendanceException.class)
    ResponseEntity<?> error(AttendanceException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "SESSION_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "ATTENDANCE_READ_DENIED", "ATTENDANCE_WRITE_DENIED", "ATTENDANCE_CLOSE_DENIED" -> HttpStatus.FORBIDDEN;
            case "ATTENDANCE_SESSION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ATTENDANCE_TARGET_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ATTENDANCE_VERSION_CONFLICT", "ATTENDANCE_SESSION_CLOSED", "ATTENDANCE_SESSION_CANCELLED", "IDEMPOTENCY_KEY_REUSED" -> HttpStatus.CONFLICT;
            case "ATTENDANCE_DETAIL_REQUIRED", "ATTENDANCE_EMPTY_SESSION", "ATTENDANCE_INCOMPLETE", "ATTENDANCE_MAKEUP_NOT_ALLOWED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
                ApiEnvelope.failure(exception.code(), "출석 정보를 처리할 수 없습니다.", java.util.List.of(), RequestIdFilter.get(request)));
    }
}
