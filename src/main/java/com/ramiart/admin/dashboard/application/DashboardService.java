package com.ramiart.admin.dashboard.application;

import com.ramiart.admin.dashboard.application.DashboardRepository.AuditActivity;
import com.ramiart.admin.dashboard.application.DashboardRepository.Birthday;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DashboardService {
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    private final DashboardRepository repository;
    private final Clock clock;
    private final ZoneId studioZone;
    private final TransactionTemplate nestedRead;

    public DashboardService(DashboardRepository repository, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${admin.dashboard.studio-zone:${ADMIN_STUDIO_ZONE:Asia/Seoul}}") String studioZone) {
        this.repository = repository;
        this.clock = clock;
        this.studioZone = ZoneId.of(studioZone);
        this.nestedRead = new TransactionTemplate(transactionManager);
        this.nestedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.nestedRead.setReadOnly(true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary(LocalDate requestedDate, String requestId, Authentication authentication) {
        if (!has(authentication, "DASHBOARD_READ")) throw new DashboardException("DASHBOARD_ACCESS_DENIED");
        OffsetDateTime asOf = OffsetDateTime.now(clock).atZoneSameInstant(studioZone).toOffsetDateTime();
        LocalDate today = asOf.toLocalDate();
        if (requestedDate != null && !today.equals(requestedDate)) throw new DashboardException("DASHBOARD_DATE_NOT_SUPPORTED");

        Map<String, Object> widgets = new LinkedHashMap<>();
        List<String> failureCodes = new ArrayList<>();
        if (has(authentication, "ATTENDANCE_READ")) add(widgets, failureCodes, "attendance", "DASHBOARD_ATTENDANCE_UNAVAILABLE", requestId, () -> {
            var metrics = repository.attendance(today);
            int pending = metrics.scheduledCount() - metrics.completedCount();
            if (metrics.scheduledCount() < 0 || metrics.completedCount() < 0 || pending < 0)
                throw new IllegalStateException("attendance aggregate invariant violated");
            return Map.of("scheduledCount", metrics.scheduledCount(), "completedCount", metrics.completedCount(),
                    "pendingCount", pending, "targetUrl", "/admin/operations/attendance?date=" + today + "&attendanceStatus=PENDING");
        });
        if (has(authentication, "TUITION_BILLING_READ")) add(widgets, failureCodes, "tuition", "DASHBOARD_TUITION_UNAVAILABLE", requestId, () -> {
            var metrics = repository.overdueTuition(today);
            if (metrics.overdueStudentCount() < 0 || metrics.overdueBillingCount() < 0 || metrics.outstandingAmount() < 0)
                throw new IllegalStateException("tuition aggregate invariant violated");
            return Map.of("overdueStudentCount", metrics.overdueStudentCount(), "overdueBillingCount", metrics.overdueBillingCount(),
                    "outstandingAmount", metrics.outstandingAmount(), "currency", "KRW",
                    "targetUrl", "/admin/tuition/billings?statuses=OVERDUE");
        });
        if (has(authentication, "INQUIRY_READ")) add(widgets, failureCodes, "inquiries", "DASHBOARD_INQUIRIES_UNAVAILABLE", requestId, () -> {
            var metrics = repository.inquiries(today, studioZone);
            return Map.of("unreadCount", metrics.unreadCount(), "unansweredCount", metrics.unansweredCount(),
                    "staleCount", metrics.staleCount(), "targetUrl", "/admin/inquiries?statuses=RECEIVED,CONTACTING");
        });
        if (has(authentication, "STUDENT_READ")) add(widgets, failureCodes, "birthdays", "DASHBOARD_BIRTHDAYS_UNAVAILABLE", requestId, () -> {
            LocalDate rangeEnd = today.plusDays(6);
            List<Map<String, Object>> items = repository.birthdays(today, rangeEnd, 7).stream().map(this::birthdayView).toList();
            return Map.of("rangeEnd", rangeEnd, "items", items,
                    "targetUrl", "/admin/students?birthdayFrom=" + today.format(MONTH_DAY) + "&birthdayTo=" + rangeEnd.format(MONTH_DAY));
        });
        if (has(authentication, "AUDIT_READ")) add(widgets, failureCodes, "recentActivities", "DASHBOARD_ACTIVITY_UNAVAILABLE", requestId, () -> {
            List<Map<String, Object>> items = recentActivities(authentication);
            return Map.of("items", items);
        });

        if (!widgets.isEmpty() && failureCodes.size() == widgets.size()) throw new DashboardException("DASHBOARD_SUMMARY_FAILED");
        return new Summary(asOf, today, studioZone.getId(), widgets);
    }

    private void add(Map<String, Object> widgets, List<String> failures, String key, String errorCode,
                     String requestId, Supplier<Map<String, Object>> query) {
        try {
            Map<String, Object> data = nestedRead.execute(status -> query.get());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", "AVAILABLE");
            value.putAll(data);
            widgets.put(key, value);
        } catch (DataAccessException exception) {
            failures.add(errorCode);
            widgets.put(key, unavailable(errorCode, requestId));
        }
    }

    private List<Map<String, Object>> recentActivities(Authentication authentication) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditActivity activity : repository.recentActivities(500)) {
            ActivityRoute route = route(activity);
            if (route == null || !has(authentication, route.permission())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("auditLogId", activity.auditLogId());
            item.put("occurredAt", activity.occurredAt());
            item.put("taskId", activity.taskId());
            item.put("action", activity.action());
            item.put("actionLabel", actionLabel(activity.action()));
            item.put("targetDisplay", activity.targetDisplay() == null ? targetLabel(activity.targetType()) : activity.targetDisplay());
            item.put("actorDisplay", activity.actorDisplay() == null ? "관리자" : activity.actorDisplay());
            item.put("targetUrl", route.url());
            items.add(item);
            if (items.size() == 10) break;
        }
        return List.copyOf(items);
    }

    private Map<String, Object> birthdayView(Birthday birthday) {
        return Map.of("studentId", birthday.studentId(), "studentName", birthday.studentName(),
                "birthdayMonthDay", birthday.birthdayMonthDay(), "daysUntil", birthday.daysUntil());
    }

    private static Map<String, Object> unavailable(String code, String requestId) {
        return Map.of("status", "UNAVAILABLE", "errorCode", code, "requestId", requestId, "retryable", true);
    }

    private static ActivityRoute route(AuditActivity event) {
        boolean known = knownAction(event.action());
        String url = switch (event.targetType()) {
            case "STUDENT" -> event.action().startsWith("STUDENT_NOTE_") ? null : "/admin/students/" + event.targetId();
            case "INQUIRY" -> "/admin/inquiries/" + event.targetId();
            case "ENROLLMENT_CASE" -> "/admin/enrollments/" + event.targetId();
            case "ATTENDANCE_SESSION" -> "/admin/operations/attendance";
            case "TUITION_BILLING" -> "/admin/tuition/billings/" + event.targetId();
            case "ADMIN_USER" -> "/admin/settings/admin-accounts/" + event.targetId();
            case "MONTHLY_SCHEDULE" -> "/admin/operations/schedules";
            default -> null;
        };
        String permission = switch (event.targetType()) {
            case "STUDENT" -> "STUDENT_READ";
            case "INQUIRY" -> "INQUIRY_READ";
            case "ENROLLMENT_CASE" -> "ENROLLMENT_READ";
            case "ATTENDANCE_SESSION" -> "ATTENDANCE_READ";
            case "TUITION_BILLING" -> "TUITION_BILLING_READ";
            case "ADMIN_USER" -> "ADMIN_ACCOUNT_READ";
            case "MONTHLY_SCHEDULE" -> "SCHEDULE_READ";
            default -> null;
        };
        return permission == null ? null : new ActivityRoute(permission, known ? url : null);
    }

    private static boolean knownAction(String action) {
        return switch (action) {
            case "ATTENDANCE_RESULT_SAVED", "ATTENDANCE_SESSION_CLOSED", "INQUIRY_SUBMITTED", "INQUIRY_READ",
                    "INQUIRY_STATUS_CHANGED", "STUDENT_CREATED", "STUDENT_UPDATED", "STUDENT_STATUS_CHANGED",
                    "STUDENT_NOTE_CREATED", "STUDENT_NOTE_UPDATED", "STUDENT_NOTE_HIDDEN",
                    "MONTHLY_SCHEDULE_DRAFT_CREATED", "MONTHLY_SCHEDULE_DRAFT_SAVED", "MONTHLY_SCHEDULE_PUBLISHED",
                    "ENROLLMENT_CASE_CREATED", "ENROLLMENT_COMPLETED" -> true;
            default -> false;
        };
    }

    private static String actionLabel(String action) {
        return switch (action) {
            case "ATTENDANCE_RESULT_SAVED" -> "출석 결과 기록";
            case "ATTENDANCE_SESSION_CLOSED" -> "출석 마감";
            case "INQUIRY_SUBMITTED" -> "새 문의 접수";
            case "INQUIRY_READ" -> "문의 확인";
            case "INQUIRY_STATUS_CHANGED" -> "문의 상태 변경";
            case "STUDENT_CREATED" -> "원생 등록";
            case "STUDENT_UPDATED" -> "원생 정보 수정";
            case "STUDENT_STATUS_CHANGED" -> "원생 상태 변경";
            case "STUDENT_NOTE_CREATED" -> "원생 메모 작성";
            case "STUDENT_NOTE_UPDATED" -> "원생 메모 수정";
            case "STUDENT_NOTE_HIDDEN" -> "원생 메모 숨김";
            case "MONTHLY_SCHEDULE_PUBLISHED" -> "월간 시간표 발행";
            case "ENROLLMENT_CASE_CREATED" -> "상담 등록 생성";
            case "ENROLLMENT_COMPLETED" -> "원생 등록 완료";
            case "ADMIN_ROLE_CHANGED" -> "관리자 역할 변경";
            default -> "알 수 없는 작업";
        };
    }

    private static String targetLabel(String type) {
        return switch (type) {
            case "STUDENT" -> "원생";
            case "INQUIRY" -> "문의";
            case "ENROLLMENT_CASE" -> "상담 등록";
            case "ATTENDANCE_SESSION" -> "출석 세션";
            case "TUITION_BILLING" -> "수업료 청구";
            case "ADMIN_USER" -> "관리자 계정";
            case "MONTHLY_SCHEDULE" -> "월간 시간표";
            default -> "업무 기록";
        };
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    public record Summary(OffsetDateTime asOf, LocalDate date, String timezone, Map<String, Object> widgets) {}
    private record ActivityRoute(String permission, String url) {}
    public static final class DashboardException extends RuntimeException {
        private final String code;
        public DashboardException(String code) { this.code = code; }
        public String code() { return code; }
    }
}
