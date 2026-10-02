package com.ramiart.admin.staff.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.staff.application.StaffException;
import com.ramiart.admin.staff.application.StaffModels.AssignmentSetWrite;
import com.ramiart.admin.staff.application.StaffModels.AssignmentWrite;
import com.ramiart.admin.staff.application.StaffModels.StaffWrite;
import com.ramiart.admin.staff.application.StaffService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = StaffPersistenceIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffPersistenceIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StaffService staffService;

    private JdbcTemplate jdbcTemplate;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({StaffService.class, JdbcStaffRepository.class, AesGcmStaffPhoneProtector.class,
            JdbcAuditRecorder.class})
    static class TestConfiguration {

        @Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", StaffPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("admin.security.staff-phone-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach
    void resetDatabase() throws SQLException, IOException {
        jdbcTemplate = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            createRoleIfMissing(statement, "anon");
            createRoleIfMissing(statement, "authenticated");
            statement.execute("drop schema if exists public cascade");
            statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");
            statement.execute("create schema extensions");
        }
        try (var migrations = Files.list(projectRoot().resolve("supabase/migrations"))) {
            for (Path migration : migrations.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                PostgresScriptRunner.execute(dataSource, migration);
            }
        }
        PostgresScriptRunner.execute(dataSource, projectRoot().resolve("supabase/seed.sql"));
    }

    @AfterAll
    void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Test
    void profileWritesEncryptPhonesMaskListsAndNeverAuditSecrets() {
        UUID actorId = actorId();
        var metadata = metadata("req_staff_create");
        UUID key = UUID.randomUUID();
        StaffWrite command = activeStaff("TEACHER_01", "김라미", "라미 선생님", "010-1234-5678");

        var created = staffService.createStaff(command, actorId, key, metadata);
        var replayed = staffService.createStaff(command, actorId, key, metadata);
        var page = staffService.findStaff("라미", "ACTIVE", "TEACHER", 0, 20);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(created.phone()).isEqualTo("+821012345678");
        assertThat(page.content()).singleElement().satisfies(summary -> {
            assertThat(summary.phoneLast4()).isEqualTo("5678");
            assertThat(summary.displayName()).isEqualTo("라미 선생님");
        });
        byte[] ciphertext = jdbcTemplate.queryForObject(
                "select phone_ciphertext from staff_profile where id = ?", byte[].class, created.id());
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("01012345678").doesNotContain("+821012345678");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'STAFF_CREATED'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select details::text from audit_log where action = 'STAFF_CREATED'", String.class))
                .doesNotContain("5678").doesNotContain("+8210");

        assertThatThrownBy(() -> staffService.createStaff(
                activeStaff("TEACHER_02", "중복", "중복 선생님", "+82 10 1234 5678"),
                actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(StaffException.class).extracting("code").isEqualTo("STAFF_PHONE_DUPLICATED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from staff_profile", Integer.class)).isOne();
    }

    @Test
    void assignmentsAreVersionedPeriodGuardedAndClosedWithDeparture() {
        UUID actorId = actorId();
        UUID groupId = insertClassGroup(actorId);
        var metadata = metadata("req_staff_assignments");
        var first = staffService.createStaff(
                activeStaff("LEAD_01", "주담당", "주담당 선생님", "010-2222-3333"),
                actorId, UUID.randomUUID(), metadata);
        LocalDate start = LocalDate.now().plusDays(1);
        var assigned = staffService.replaceAssignments(first.id(), new AssignmentSetWrite(first.version(), List.of(
                new AssignmentWrite(null, groupId, "LEAD", start, null, null))),
                actorId, UUID.randomUUID(), metadata);

        assertThat(assigned.assignments()).singleElement().satisfies(value -> {
            assertThat(value.role()).isEqualTo("LEAD");
            assertThat(value.classGroupId()).isEqualTo(groupId);
        });
        assertThat(assigned.version()).isEqualTo(first.version() + 1);
        assertThatThrownBy(() -> staffService.replaceAssignments(first.id(),
                new AssignmentSetWrite(first.version(), List.of()), actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(StaffException.class).extracting("code").isEqualTo("STAFF_VERSION_CONFLICT");
        assertThatThrownBy(() -> staffService.replaceAssignments(first.id(),
                new AssignmentSetWrite(assigned.version(), List.of(new AssignmentWrite(
                        null, groupId, "ASSISTANT", LocalDate.now().minusDays(10), null, null))),
                actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(StaffException.class).extracting("code")
                .isEqualTo("STAFF_PERIOD_OUTSIDE_EMPLOYMENT");

        var second = staffService.createStaff(
                activeStaff("LEAD_02", "다른", "다른 선생님", "010-4444-5555"),
                actorId, UUID.randomUUID(), metadata);
        assertThatThrownBy(() -> staffService.replaceAssignments(second.id(),
                new AssignmentSetWrite(second.version(), List.of(new AssignmentWrite(
                        null, groupId, "LEAD", start.plusDays(1), null, null))),
                actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(DataIntegrityViolationException.class);

        StaffWrite departure = new StaffWrite(
                assigned.staffCode(), assigned.name(), assigned.displayName(), assigned.jobTitle(), assigned.phone(),
                assigned.hiredOn(), start.plusDays(10), "INACTIVE", assigned.adminUserId(), assigned.version(), false);
        assertThatThrownBy(() -> staffService.updateStaff(
                assigned.id(), departure, actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(StaffException.class).extracting("code")
                .isEqualTo("STAFF_FUTURE_ASSIGNMENT_EXISTS");

        StaffWrite confirmed = new StaffWrite(
                departure.staffCode(), departure.name(), departure.displayName(), departure.jobTitle(), departure.phone(),
                departure.hiredOn(), departure.leftOn(), departure.status(), departure.adminUserId(),
                departure.version(), true);
        var inactive = staffService.updateStaff(assigned.id(), confirmed, actorId, UUID.randomUUID(), metadata);
        assertThat(inactive.status()).isEqualTo("INACTIVE");
        assertThat(inactive.assignments()).singleElement()
                .extracting("effectiveTo").isEqualTo(departure.leftOn());
    }

    @Test
    void databaseRejectsChangingIssuedStaffCode() {
        UUID actorId = actorId();
        var created = staffService.createStaff(
                activeStaff("FIXED_01", "고정", "고정 선생님", "010-7777-8888"),
                actorId, UUID.randomUUID(), metadata("req_staff_code"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update staff_profile set staff_code = 'CHANGED' where id = ?", created.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("staff code cannot be changed after issuance");
    }

    private UUID insertClassGroup(UUID actorId) {
        UUID courseId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into course (id, code, name, display_order, active, created_by, updated_by)
                values (?, 'STAFF_TEST', '강사 검증 과정', 99, true, ?, ?)
                """, courseId, actorId, actorId);
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into class_group (
                  id, course_id, code, name, room_code, capacity, starts_on, status, created_by, updated_by)
                values (?, ?, 'STAFF_TEST_A', '강사 검증반', 'ROOM_A', 10, ?, 'ACTIVE', ?, ?)
                """, groupId, courseId, LocalDate.now(), actorId, actorId);
        return groupId;
    }

    private static StaffWrite activeStaff(String code, String name, String displayName, String phone) {
        return new StaffWrite(code, name, displayName, "TEACHER", phone,
                LocalDate.now(), null, "ACTIVE", null, null, false);
    }

    private UUID actorId() {
        return jdbcTemplate.queryForObject("select id from admin_user", UUID.class);
    }

    private static StaffService.RequestMetadata metadata(String requestId) {
        return new StaffService.RequestMetadata(requestId, "127.0.0.1", "integration-test");
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var resultSet = statement.executeQuery(
                "select exists(select 1 from pg_roles where rolname = '" + role + "')")) {
            resultSet.next();
            if (!resultSet.getBoolean(1)) statement.execute("create role " + role);
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("supabase"))) return current;
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("supabase"))) return parent;
        throw new IllegalStateException("supabase directory was not found");
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl() {
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException exception) {
            throw new IllegalStateException("embedded PostgreSQL URL lookup failed", exception);
        }
    }
}
