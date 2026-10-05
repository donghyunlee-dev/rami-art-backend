package com.ramiart.admin.dashboard.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.dashboard.application.DashboardService;
import com.ramiart.admin.dashboard.application.DashboardService.DashboardException;
import com.ramiart.admin.dashboard.application.MonthlyDashboardService;
import com.ramiart.admin.dashboard.application.MonthlyDashboardService.MonthlyDashboardException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
public final class DashboardController {
    private final DashboardService service;
    private final MonthlyDashboardService monthlyService;
    public DashboardController(DashboardService service, MonthlyDashboardService monthlyService) { this.service = service; this.monthlyService = monthlyService; }

    @GetMapping("/summary")
    ResponseEntity<ApiEnvelope<DashboardService.Summary>> summary(@RequestParam(required = false) LocalDate date,
            Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(ApiEnvelope.success(service.summary(date, RequestIdFilter.get(request), authentication), RequestIdFilter.get(request)));
    }

    @GetMapping("/monthly")
    ResponseEntity<ApiEnvelope<MonthlyDashboardService.Summary>> monthly(@RequestParam(required = false) String month,
            Authentication authentication, HttpServletRequest request) {
        YearMonth selected = null;
        if (month != null) {
            try { selected = YearMonth.parse(month); }
            catch (java.time.format.DateTimeParseException exception) {
                throw new MonthlyDashboardException("DASHBOARD_MONTH_NOT_SUPPORTED");
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(ApiEnvelope.success(monthlyService.monthly(selected, RequestIdFilter.get(request), authentication), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(DashboardException.class)
    ResponseEntity<ApiEnvelope<Void>> error(DashboardException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "DASHBOARD_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "DASHBOARD_SUMMARY_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(ApiEnvelope.failure(exception.code(), "대시보드 데이터를 조회할 수 없습니다.", java.util.List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(MonthlyDashboardException.class)
    ResponseEntity<ApiEnvelope<Void>> monthlyError(MonthlyDashboardException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "DASHBOARD_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            case "DASHBOARD_MONTHLY_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(ApiEnvelope.failure(exception.code(), "월간 대시보드 데이터를 조회할 수 없습니다.", java.util.List.of(), RequestIdFilter.get(request)));
    }
}
