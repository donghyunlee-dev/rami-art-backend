package com.ramiart.admin.attendance.api;

import static com.ramiart.admin.attendance.application.AttendanceModels.*;
import com.ramiart.admin.attendance.application.AttendanceService;
import com.ramiart.admin.attendance.application.AttendanceService.AttendanceException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
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
    @ExceptionHandler(AttendanceException.class)
    ResponseEntity<?> error(AttendanceException exception, HttpServletRequest request) {
        HttpStatus status = "ATTENDANCE_DATE_OUT_OF_RANGE".equals(exception.code()) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("success", false, "error", Map.of("code", exception.code()), "requestId", RequestIdFilter.get(request)));
    }
}
