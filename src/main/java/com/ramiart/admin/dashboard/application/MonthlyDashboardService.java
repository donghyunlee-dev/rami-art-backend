package com.ramiart.admin.dashboard.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MonthlyDashboardService {
    private final MonthlyDashboardRepository repository;
    private final Clock clock;
    private final ZoneId zone;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate widget;

    public MonthlyDashboardService(MonthlyDashboardRepository repository, Clock clock, DataSource dataSource,
            @Value("${admin.dashboard.studio-zone:${ADMIN_STUDIO_ZONE:Asia/Seoul}}") String studioZone) {
        this.repository = repository;
        this.clock = clock;
        this.zone = ZoneId.of(studioZone);
        var manager = new DataSourceTransactionManager(dataSource);
        this.snapshot = new TransactionTemplate(manager);
        this.snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.widget = new TransactionTemplate(manager);
        this.widget.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.widget.setReadOnly(true);
    }

    public Summary monthly(YearMonth requested, String requestId, Authentication authentication) {
        return snapshot.execute(status -> load(requested, requestId, authentication));
    }

    private Summary load(YearMonth requested, String requestId, Authentication authentication) {
        if (!has(authentication, "DASHBOARD_READ")) throw new MonthlyDashboardException("DASHBOARD_ACCESS_DENIED");
        OffsetDateTime asOf = OffsetDateTime.now(clock).atZoneSameInstant(zone).toOffsetDateTime();
        YearMonth current = YearMonth.from(asOf);
        YearMonth month = requested == null ? current : requested;
        if (month.isAfter(current) || month.isBefore(current.minusMonths(11)))
            throw new MonthlyDashboardException("DASHBOARD_MONTH_NOT_SUPPORTED");

        Map<String, Object> widgets = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        add(widgets, failures, "tuition", "DASHBOARD_TUITION_UNAVAILABLE", requestId, authentication,
                "TUITION_BILLING_READ", () -> repository.tuition(month));
        add(widgets, failures, "attendance", "DASHBOARD_ATTENDANCE_UNAVAILABLE", requestId, authentication,
                "ATTENDANCE_READ", () -> repository.attendance(month));
        add(widgets, failures, "lessons", "DASHBOARD_LESSONS_UNAVAILABLE", requestId, authentication,
                "LESSON_PLAN_READ", () -> repository.lessons(month));
        add(widgets, failures, "enrollment", "DASHBOARD_ENROLLMENT_UNAVAILABLE", requestId, authentication,
                "ENROLLMENT_READ", () -> repository.enrollment(month, zone));
        add(widgets, failures, "capacity", "DASHBOARD_CAPACITY_UNAVAILABLE", requestId, authentication,
                "COURSE_READ", () -> repository.capacity(asOf.toLocalDate()));
        if (!widgets.isEmpty() && failures.size() == widgets.size())
            throw new MonthlyDashboardException("DASHBOARD_MONTHLY_FAILED");
        return new Summary(month.toString(), zone.getId(), asOf, widgets);
    }

    private void add(Map<String, Object> widgets, List<String> failures, String key, String errorCode,
            String requestId, Authentication authentication, String permission, Supplier<Map<String, Object>> query) {
        if (!has(authentication, permission)) return;
        try {
            Map<String, Object> result = widget.execute(status -> query.get());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", "AVAILABLE");
            value.putAll(result);
            widgets.put(key, value);
        } catch (DataAccessException exception) {
            failures.add(errorCode);
            widgets.put(key, Map.of("status", "UNAVAILABLE", "errorCode", errorCode,
                    "requestId", requestId, "retryable", true));
        }
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    public record Summary(String month, String timezone, OffsetDateTime asOf, Map<String, Object> widgets) {}

    public static final class MonthlyDashboardException extends RuntimeException {
        private final String code;
        public MonthlyDashboardException(String code) { this.code = code; }
        public String code() { return code; }
    }
}
