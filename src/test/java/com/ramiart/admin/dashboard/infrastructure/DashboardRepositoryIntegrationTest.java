package com.ramiart.admin.dashboard.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.dashboard.application.DashboardRepository;
import com.ramiart.admin.dashboard.application.DashboardRepository.AttendanceMetrics;
import com.ramiart.admin.dashboard.application.DashboardRepository.AuditActivity;
import com.ramiart.admin.dashboard.application.DashboardRepository.Birthday;
import com.ramiart.admin.dashboard.application.DashboardService;
import com.ramiart.admin.dashboard.application.DashboardRepository.InquiryMetrics;
import com.ramiart.admin.dashboard.application.DashboardRepository.TuitionMetrics;
import com.ramiart.admin.dashboard.application.MonthlyDashboardService;
import com.ramiart.admin.dashboard.infrastructure.JdbcMonthlyDashboardRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardRepositoryIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private final DataSource dataSource = POSTGRES.getPostgresDatabase();
    private JdbcTemplate jdbc;
    private DashboardRepository repository;
    private UUID actorId;

    @BeforeAll
    void applyCanonicalSchemaAndSeed() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            createRoleIfMissing(statement, "anon");
            createRoleIfMissing(statement, "authenticated");
            statement.execute("drop schema if exists public cascade");
            statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");
            statement.execute("create schema extensions");
        }
        Path root = projectRoot();
        try (var migrations = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : migrations.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                PostgresScriptRunner.execute(dataSource, migration);
            }
        }
        PostgresScriptRunner.execute(dataSource, root.resolve("supabase/seed.sql"));
        actorId = jdbc.queryForObject("select id from admin_user order by email limit 1", UUID.class);
        repository = new JdbcDashboardRepository(JdbcClient.create(dataSource));
    }

    @AfterAll
    void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test
    void dashboardWidgetsAggregateCanonicalPostgresSourcesAndUpcomingBirthdays() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        LocalDate birthday = today.plusDays(2).withYear(today.getYear() - 8);
        if (!birthday.isBefore(today)) birthday = birthday.minusYears(1);
        UUID studentId = UUID.randomUUID();
        jdbc.update("insert into student(id,student_name,student_name_search,birthday,joined_at,status,created_by,updated_by) values(?,?,?,?,?,'ACTIVE',?,?)",
                studentId, "대시보드 통합 원생", "대시보드통합원생", birthday, birthday.plusYears(6), actorId, actorId);

        var attendance = repository.attendance(today);
        var tuition = repository.overdueTuition(today);
        var inquiries = repository.inquiries(today, java.time.ZoneId.of("Asia/Seoul"));
        var birthdays = repository.birthdays(today, today.plusDays(6), 7);
        var activities = repository.recentActivities(20);

        assertThat(attendance.scheduledCount()).isZero();
        assertThat(attendance.completedCount()).isZero();
        assertThat(tuition.overdueStudentCount()).isZero();
        assertThat(tuition.overdueBillingCount()).isZero();
        assertThat(tuition.outstandingAmount()).isZero();
        assertThat(inquiries.unreadCount()).isZero();
        assertThat(inquiries.unansweredCount()).isZero();
        assertThat(inquiries.staleCount()).isZero();
        assertThat(birthdays).extracting(DashboardRepository.Birthday::studentId).containsExactly(studentId);
        assertThat(activities).isEmpty();

        new JdbcAuditRecorder(JdbcClient.create(dataSource), new ObjectMapper()).record(new AuditRecorder.Event(
                Instant.now(), "req_dashboard_test", "MGT-STUDENT-CREATE", "OPERATION", "ADMIN", actorId,
                null, "STUDENT_CREATED", "STUDENT", studentId, "SUCCESS", null, "127.0.0.1",
                "integration-test", Map.of()));
        var recordedActivity = repository.recentActivities(10).getFirst();
        assertThat(recordedActivity.actorDisplay()).isNotBlank();
        assertThat(recordedActivity.targetDisplay()).contains("•").doesNotContain("대시보드 통합 원생");

        var service = new DashboardService(repository, Clock.systemUTC(), dataSource, "Asia/Seoul");
        var authentication = new TestingAuthenticationToken("dashboard-test", "",
                "DASHBOARD_READ", "ATTENDANCE_READ", "TUITION_BILLING_READ", "INQUIRY_READ", "STUDENT_READ", "AUDIT_READ");
        var summary = service.summary(today, "req_dashboard_test", authentication);
        assertThat(summary).isNotNull();
        assertThat(summary.widgets()).containsKeys("attendance", "tuition", "inquiries", "birthdays", "recentActivities");
        assertThat(summary.widgets().get("birthdays").toString()).contains("대시보드 통합 원생");
        assertThat(summary.widgets().get("recentActivities").toString())
                .contains("원생 등록", recordedActivity.targetDisplay(), recordedActivity.actorDisplay());
        var noDashboardPermission = new TestingAuthenticationToken("dashboard-test", "", "STUDENT_READ");
        assertThatThrownBy(() -> service.summary(today, "req_dashboard_forbidden", noDashboardPermission))
                .isInstanceOf(DashboardService.DashboardException.class)
                .extracting("code").isEqualTo("DASHBOARD_ACCESS_DENIED");
        assertThatThrownBy(() -> service.summary(today.minusDays(1), "req_dashboard_date", authentication))
                .isInstanceOf(DashboardService.DashboardException.class)
                .extracting("code").isEqualTo("DASHBOARD_DATE_NOT_SUPPORTED");
    }

    @Test
    void failedWidgetRollsBackToSavepointAndLaterWidgetsStillQuery() {
        DashboardRepository failingAttendance = new DashboardRepository() {
            @Override public AttendanceMetrics attendance(LocalDate date) {
                jdbc.execute("select 1 / 0");
                return repository.attendance(date);
            }
            @Override public TuitionMetrics overdueTuition(LocalDate today) { return repository.overdueTuition(today); }
            @Override public InquiryMetrics inquiries(LocalDate today, java.time.ZoneId zone) { return repository.inquiries(today, zone); }
            @Override public java.util.List<Birthday> birthdays(LocalDate today, LocalDate end, int limit) {
                return repository.birthdays(today, end, limit);
            }
            @Override public java.util.List<AuditActivity> recentActivities(int limit) { return repository.recentActivities(limit); }
        };
        var service = new DashboardService(failingAttendance, Clock.systemUTC(), dataSource, "Asia/Seoul");
        var authentication = new TestingAuthenticationToken("dashboard-test", "",
                "DASHBOARD_READ", "ATTENDANCE_READ", "TUITION_BILLING_READ");

        var summary = service.summary(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")),
                "req_dashboard_savepoint", authentication);

        assertThat(summary).isNotNull();
        assertThat(summary.widgets().get("attendance").toString()).contains("UNAVAILABLE");
        assertThat(summary.widgets().get("tuition").toString()).contains("AVAILABLE");
    }

    @Test
    void monthlyDashboardUsesAllowedWindowAndOmitsWidgetsWithoutSourcePermission() {
        var service = new MonthlyDashboardService(new JdbcMonthlyDashboardRepository(JdbcClient.create(dataSource)),
                Clock.systemUTC(), dataSource, "Asia/Seoul");
        var now = java.time.YearMonth.now(Clock.systemUTC());
        var permitted = new TestingAuthenticationToken("dashboard-test", "", "DASHBOARD_READ", "ATTENDANCE_READ");
        var monthlyRepository = new JdbcMonthlyDashboardRepository(JdbcClient.create(dataSource));
        Map<String,Object> tuition = monthlyRepository.tuition(now);
        assertThat(tuition).containsKeys("chargeAmount", "paidAmount", "refundAmount", "outstandingAmount", "creditAmount", "previousMonth");
        assertThat(((Map<?,?>)tuition.get("previousMonth")).keySet().toString())
                .contains("chargeAmount", "paidAmount", "refundAmount", "outstandingAmount", "creditAmount");
        assertThat(monthlyRepository.attendance(now)).containsKeys("targetCount", "attendedCount", "absentCount", "pendingCount", "rate", "daily");
        assertThat(monthlyRepository.lessons(now)).containsKeys("scheduledCount", "plannedCount", "closedCount", "finalizedLogCount", "missingLogCount");
        assertThat(monthlyRepository.enrollment(now, java.time.ZoneId.of("Asia/Seoul"))).containsKeys("newCount", "trialCount", "waitlistedCount", "enrolledCount", "lostCount", "conversionRate");
        assertThat(monthlyRepository.capacity(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))).containsKey("items");

        var summary = service.monthly(now, "req_monthly_dashboard", permitted);

        assertThat(summary.widgets()).containsOnlyKeys("attendance");
        assertThat(summary.widgets().get("attendance").toString())
                .contains("targetCount", "attendedCount", "pendingCount", "daily", now.toString());
        assertThatThrownBy(() -> service.monthly(now.minusMonths(12), "req_monthly_old", permitted))
                .isInstanceOf(MonthlyDashboardService.MonthlyDashboardException.class)
                .extracting("code").isEqualTo("DASHBOARD_MONTH_NOT_SUPPORTED");
        assertThatThrownBy(() -> service.monthly(now, "req_monthly_forbidden",
                new TestingAuthenticationToken("dashboard-test", "", "ATTENDANCE_READ")))
                .isInstanceOf(MonthlyDashboardService.MonthlyDashboardException.class)
                .extracting("code").isEqualTo("DASHBOARD_ACCESS_DENIED");
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("supabase/migrations"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("supabase migrations directory not found");
        return current;
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var rows = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + role + "')")) {
            rows.next();
            if (!rows.getBoolean(1)) statement.execute("create role " + role);
        }
    }
}
