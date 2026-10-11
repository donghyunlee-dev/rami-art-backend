package com.ramiart.admin.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.course.application.CourseException;
import com.ramiart.admin.course.application.CourseModels.ClassGroupWrite;
import com.ramiart.admin.course.application.CourseModels.CourseWrite;
import com.ramiart.admin.course.application.CourseService;
import com.ramiart.admin.dev.PostgresScriptRunner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = CoursePersistenceIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoursePersistenceIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CourseService courseService;

    private JdbcTemplate jdbcTemplate;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({CourseService.class, JdbcCourseRepository.class, JdbcAuditRecorder.class})
    static class TestConfiguration {

        @Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CoursePersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
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
    void writesAreIdempotentVersionedGuardedAndAudited() {
        UUID actorId = jdbcTemplate.queryForObject("select id from admin_user", UUID.class);
        var metadata = new CourseService.RequestMetadata("req_course_create", "127.0.0.1", "integration-test");
        UUID createKey = UUID.randomUUID();
        var command = new CourseWrite("DRAWING", "드로잉", "기초 관찰 드로잉", "초등 이상", null, null, 10, true, null);

        var created = courseService.createCourse(command, actorId, createKey, metadata);
        var replayed = courseService.createCourse(command, actorId, createKey, metadata);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from course", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'COURSE_CREATED'", Integer.class)).isOne();

        var group = courseService.createClassGroup(created.id(), new ClassGroupWrite(
                "DRAWING_A", "드로잉 A", "ROOM_A", 8, true, 30,
                LocalDate.now(), null, "ACTIVE", null), actorId, UUID.randomUUID(), metadata);
        assertThat(group.availableSeats()).isEqualTo(8);
        var occupancy = courseService.findOccupancy(group.id(), LocalDate.now(), LocalDate.now().plusDays(30));
        assertThat(occupancy.regularOccupancy()).isZero();
        assertThat(occupancy.reservedMakeupCount()).isZero();
        assertThat(occupancy.availableSeats()).isEqualTo(8);

        assertThatThrownBy(() -> courseService.updateCourse(created.id(), new CourseWrite(
                created.code(), created.name(), created.description(), created.ageGuide(),
                created.sessionDurationMinutes(), created.weeklySessions(), created.displayOrder(), false, created.version()), actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class)
                .extracting("code").isEqualTo("COURSE_HAS_ACTIVE_GROUPS");

        var updated = courseService.updateClassGroup(group.id(), new ClassGroupWrite(
                group.code(), "드로잉 심화 A", group.roomCode(), group.capacity(), group.waitlistEnabled(),
                group.makeupValidDays(), group.startsOn(), group.endsOn(), group.status(), group.version()),
                actorId, UUID.randomUUID(), metadata);
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> courseService.updateClassGroup(group.id(), new ClassGroupWrite(
                group.code(), "동시 수정", group.roomCode(), group.capacity(), group.waitlistEnabled(),
                group.makeupValidDays(), group.startsOn(), group.endsOn(), group.status(), group.version()),
                actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class)
                .extracting("code").isEqualTo("COURSE_VERSION_CONFLICT");

        var deletable = courseService.createClassGroup(created.id(), new ClassGroupWrite(
                "DRAWING_DRAFT", "삭제할 준비반", "ROOM_B", 6, true, 30,
                LocalDate.now(), null, "DRAFT", null), actorId, UUID.randomUUID(), metadata);
        UUID deleteKey = UUID.randomUUID();
        courseService.deleteClassGroup(deletable.id(), deletable.version(), actorId, deleteKey, metadata);
        courseService.deleteClassGroup(deletable.id(), deletable.version(), actorId, deleteKey, metadata);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'CLASS_GROUP_DELETED'", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from class_group where id = ?", Integer.class, deletable.id())).isZero();

        var referenced = courseService.createClassGroup(created.id(), new ClassGroupWrite(
                "DRAWING_REF", "참조된 준비반", "ROOM_C", 6, true, 30,
                LocalDate.now(), null, "DRAFT", null), actorId, UUID.randomUUID(), metadata);
        jdbcTemplate.update("insert into schedule_slot (id, class_group_id, created_by) values (?, ?, ?)",
                UUID.randomUUID(), referenced.id(), actorId);
        assertThatThrownBy(() -> courseService.deleteClassGroup(
                referenced.id(), referenced.version(), actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class)
                .extracting("code").isEqualTo("CLASS_GROUP_REFERENCED");
        assertThatThrownBy(() -> courseService.deleteClassGroup(
                group.id(), updated.version(), actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class)
                .extracting("code").isEqualTo("CLASS_GROUP_NOT_DRAFT");
    }

    @Test
    void futureAssignmentsAndReservedMakeupProtectCapacity() {
        UUID actorId = jdbcTemplate.queryForObject("select id from admin_user", UUID.class);
        var metadata = new CourseService.RequestMetadata("req_course_occupancy", "127.0.0.1", "integration-test");
        var course = courseService.createCourse(
                new CourseWrite("PAINTING", "회화", null, null, null, null, 30, true, null),
                actorId, UUID.randomUUID(), metadata);
        var group = courseService.createClassGroup(course.id(), new ClassGroupWrite(
                "PAINTING_A", "회화 A", "ROOM_A", 4, true, 30,
                LocalDate.now(), null, "ACTIVE", null), actorId, UUID.randomUUID(), metadata);

        LocalDate originDate = LocalDate.now().plusMonths(1).withDayOfMonth(10);
        LocalDate reservedDate = originDate.plusDays(1);
        UUID slotId = UUID.randomUUID();
        jdbcTemplate.update("insert into schedule_slot (id, class_group_id, created_by) values (?, ?, ?)",
                slotId, group.id(), actorId);
        UUID firstStudent = insertStudent("미래원생A", actorId);
        UUID secondStudent = insertStudent("미래원생B", actorId);
        for (UUID studentId : java.util.List.of(firstStudent, secondStudent)) {
            jdbcTemplate.update("""
                    insert into student_schedule_assignment
                      (id, student_id, schedule_slot_id, effective_from, created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), studentId, slotId, originDate.minusDays(1), actorId, actorId);
        }
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into monthly_schedule (id, year_month, revision, status, created_by)
                values (?, ?, 1, 'DRAFT', ?)
                """, scheduleId, String.format("%04d-%02d", originDate.getYear(), originDate.getMonthValue()), actorId);
        UUID originSession = insertMakeupSession(scheduleId, group.id(), originDate, "10:00", "11:00");
        UUID reservedSession = insertMakeupSession(scheduleId, group.id(), reservedDate, "12:00", "13:00");
        UUID attendanceId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into attendance_session_student
                  (attendance_session_id, student_id, student_name_snapshot, display_order)
                values (?, ?, '미래원생A', 0)
                """, originSession, firstStudent);
        jdbcTemplate.update("""
                insert into student_attendance
                  (id, attendance_session_id, student_id, status, reason, makeup_eligible, checked_by, updated_by)
                values (?, ?, ?, 'ABSENT', '보강 검증 결석', true, ?, ?)
                """, attendanceId, originSession, firstStudent, actorId, actorId);
        jdbcTemplate.update("""
                insert into makeup_case
                  (id, student_id, origin_attendance_id, origin_session_id, class_group_id, status,
                   expires_on, reserved_session_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?)
                """, UUID.randomUUID(), firstStudent, attendanceId, originSession, group.id(),
                reservedDate.plusDays(30), reservedSession, actorId, actorId);

        var occupancy = courseService.findOccupancy(group.id(), originDate.minusDays(1), reservedDate.plusDays(1));
        assertThat(occupancy.regularOccupancy()).isEqualTo(2);
        assertThat(occupancy.reservedMakeupCount()).isOne();
        assertThat(occupancy.availableSeats()).isOne();

        assertThatThrownBy(() -> courseService.updateClassGroup(group.id(), new ClassGroupWrite(
                group.code(), group.name(), group.roomCode(), 2, group.waitlistEnabled(),
                group.makeupValidDays(), group.startsOn(), group.endsOn(), group.status(), group.version()),
                actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class)
                .extracting("code").isEqualTo("CLASS_CAPACITY_BELOW_OCCUPANCY");
        var resized = courseService.updateClassGroup(group.id(), new ClassGroupWrite(
                group.code(), group.name(), group.roomCode(), 3, group.waitlistEnabled(),
                group.makeupValidDays(), group.startsOn(), group.endsOn(), group.status(), group.version()),
                actorId, UUID.randomUUID(), metadata);
        assertThat(resized.capacity()).isEqualTo(3);
    }

    @Test
    void invalidAndDuplicateCodesLeaveNoPartialRows() {
        UUID actorId = jdbcTemplate.queryForObject("select id from admin_user", UUID.class);
        var metadata = new CourseService.RequestMetadata("req_course_validation", "127.0.0.1", "integration-test");
        var valid = new CourseWrite("CERAMIC", "도예", null, null, null, null, 20, true, null);
        courseService.createCourse(valid, actorId, UUID.randomUUID(), metadata);

        assertThatThrownBy(() -> courseService.createCourse(valid, actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class).extracting("code").isEqualTo("COURSE_CODE_DUPLICATED");
        assertThatThrownBy(() -> courseService.createCourse(
                new CourseWrite("!", "잘못된 과정", null, null, null, null, 21, true, null),
                actorId, UUID.randomUUID(), metadata))
                .isInstanceOf(CourseException.class).extracting("code").isEqualTo("COURSE_VALIDATION_ERROR");
        assertThat(jdbcTemplate.queryForObject("select count(*) from course", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from idempotency_record", Integer.class)).isOne();
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var resultSet = statement.executeQuery(
                "select exists(select 1 from pg_roles where rolname = '" + role + "')")) {
            resultSet.next();
            if (!resultSet.getBoolean(1)) statement.execute("create role " + role);
        }
    }

    private UUID insertStudent(String name, UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into student
                  (id, student_name, student_name_search, joined_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?)
                """, id, name, name.toLowerCase(), LocalDate.now(), actorId, actorId);
        return id;
    }

    private UUID insertMakeupSession(UUID scheduleId, UUID classGroupId, LocalDate date,
            String startsAt, String endsAt) {
        UUID overrideId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into schedule_override
                  (id, monthly_schedule_id, class_group_id, target_date, type,
                   start_time, end_time, title, room_code, reason)
                values (?, ?, ?, ?, 'MAKEUP', ?::time, ?::time, '보강 검증', 'ROOM_A', '통합 테스트')
                """, overrideId, scheduleId, classGroupId, date, startsAt, endsAt);
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime start = date.atTime(java.time.LocalTime.parse(startsAt)).atOffset(ZoneOffset.UTC);
        OffsetDateTime end = date.atTime(java.time.LocalTime.parse(endsAt)).atOffset(ZoneOffset.UTC);
        jdbcTemplate.update("""
                insert into attendance_session
                  (id, schedule_override_id, class_group_id, attendance_date,
                   class_name_snapshot, room_code_snapshot, starts_at, ends_at, status)
                values (?, ?, ?, ?, '보강 검증반', 'ROOM_A', ?, ?, 'OPEN')
                """, sessionId, overrideId, classGroupId, date, start, end);
        return sessionId;
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
