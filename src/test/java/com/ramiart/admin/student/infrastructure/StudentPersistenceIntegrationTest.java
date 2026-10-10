package com.ramiart.admin.student.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.student.application.StudentException;
import com.ramiart.admin.student.application.StudentModels.GuardianWrite;
import com.ramiart.admin.student.application.StudentModels.NoteCreate;
import com.ramiart.admin.student.application.StudentModels.NoteHide;
import com.ramiart.admin.student.application.StudentModels.NoteUpdate;
import com.ramiart.admin.student.application.StudentModels.StatusWrite;
import com.ramiart.admin.student.application.StudentModels.StudentCreate;
import com.ramiart.admin.student.application.StudentModels.StudentUpdate;
import com.ramiart.admin.student.application.StudentService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest(classes = StudentPersistenceIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudentPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    @Autowired DataSource dataSource;
    @Autowired StudentService service;
    private JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({StudentService.class, JdbcStudentRepository.class, AesGcmStudentDataProtector.class,
            JdbcAuditRecorder.class})
    static class TestConfiguration {
        @Bean java.time.Clock clock() { return java.time.Clock.systemUTC(); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", StudentPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("admin.security.staff-phone-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            createRole(statement, "anon");
            createRole(statement, "authenticated");
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

    @AfterAll void close() throws IOException { POSTGRES.close(); }

    @Test
    void createUpdateAndReplayAreAtomicAndKeepPiiOutOfAudit() {
        UUID actor = actor();
        UUID key = UUID.randomUUID();
        StudentCreate command = createCommand("김하늘", "010-1234-5678");

        var created = service.create(command, actor, key, metadata("student-create"));
        var replayed = service.create(command, actor, key, metadata("student-create-replay"));

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(created.guardians()).singleElement().satisfies(guardian -> {
            assertThat(guardian.phone()).isEqualTo("+821012345678");
            assertThat(guardian.maskedPhone()).isEqualTo("***-****-5678");
        });
        byte[] encrypted = jdbc.queryForObject(
                "select phone_ciphertext from guardian_contact where student_id=?", byte[].class, created.id());
        assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain("12345678");
        assertThat(jdbc.queryForObject("select count(*) from student where id=?", Integer.class, created.id())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from student_status_history where student_id=?", Integer.class, created.id())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='STUDENT_CREATED'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select details::text from audit_log where action='STUDENT_CREATED'", String.class))
                .doesNotContain("김하늘").doesNotContain("5678");

        GuardianWrite guardian = new GuardianWrite(created.guardians().getFirst().id(), "김보호", "MOTHER",
                null, "010-9999-0000", null, "SMS", true, 0);
        var updated = service.update(created.id(), new StudentUpdate("김하늘", command.birthday(), "새 학교",
                command.joinedAt(), created.version(), List.of(guardian)), actor, UUID.randomUUID(), metadata("student-update"));
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(updated.schoolName()).isEqualTo("새 학교");
        assertThatThrownBy(() -> service.update(created.id(), new StudentUpdate("충돌", command.birthday(), null,
                command.joinedAt(), created.version(), List.of(guardian)), actor, UUID.randomUUID(), metadata("student-conflict")))
                .isInstanceOf(StudentException.class).extracting("code").isEqualTo("STUDENT_VERSION_CONFLICT");
    }

    @Test
    void statusHistoryAndNotesAreVersionedEncryptedAndAppendOnly() {
        UUID actor = actor();
        var student = service.create(createCommand("박바다", "010-2222-3333"), actor,
                UUID.randomUUID(), metadata("student-create"));
        LocalDate studioToday = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        StatusWrite previewInput = new StatusWrite("PAUSED", studioToday, "가족 일정으로 일시 중단",
                student.version(), null);
        var preview = service.preview(student.id(), previewInput);
        var changed = service.changeStatus(student.id(), new StatusWrite("PAUSED", studioToday,
                "가족 일정으로 일시 중단", student.version(), preview.previewToken()), actor,
                UUID.randomUUID(), metadata("student-status"));
        assertThat(changed.toStatus()).isEqualTo("PAUSED");
        assertThat(service.detail(student.id()).status()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> jdbc.update("delete from student_status_history where id=?", changed.historyId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("student status history is append only");

        UUID noteKey = UUID.randomUUID();
        var note = service.createNote(student.id(), new NoteCreate("첫 상담 내용을 기록했습니다."), actor,
                noteKey, metadata("student-note"));
        var replayed = service.createNote(student.id(), new NoteCreate("첫 상담 내용을 기록했습니다."), actor,
                noteKey, metadata("student-note-replay"));
        assertThat(replayed.id()).isEqualTo(note.id());
        assertThat(new String(jdbc.queryForObject("select content_ciphertext from student_note where id=?",
                byte[].class, note.id()), StandardCharsets.UTF_8)).doesNotContain("상담");
        var edited = service.updateNote(note.id(), new NoteUpdate("정정한 상담 내용입니다.", note.version()),
                actor, metadata("student-note-update"));
        assertThat(edited.version()).isEqualTo(note.version() + 1);
        service.hideNote(note.id(), new NoteHide("잘못된 원생에게 등록함", edited.version()), actor,
                metadata("student-note-hide"));
        assertThat(service.notes(student.id(), null, 5, actor).items()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'STUDENT_NOTE_%'",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void listCanFilterByCurrentlyAssignedClassName() {
        UUID actor = actor();
        var student = service.create(createCommand("윤하늘", "010-3333-4444"), actor,
                UUID.randomUUID(), metadata("student-class-filter-create"));
        UUID course = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID slot = UUID.randomUUID();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        jdbc.update("insert into course(id,code,name,display_order,created_by,updated_by) values(?,?,?,?,?,?)",
                course, "STU_" + course.toString().substring(0, 8).toUpperCase(), "초등 과정",
                Math.floorMod(course.hashCode(), 100000), actor, actor);
        jdbc.update("insert into class_group(id,course_id,code,name,room_code,capacity,starts_on,status,created_by,updated_by) values(?,?,?,?,?,10,?,'ACTIVE',?,?)",
                group, course, "CLS_" + group.toString().substring(0, 8).toUpperCase(), "초등 A반", "ROOM_A", today.minusDays(30), actor, actor);
        jdbc.update("insert into schedule_slot(id,class_group_id,created_by) values(?,?,?)", slot, group, actor);
        jdbc.update("insert into student_schedule_assignment(id,student_id,schedule_slot_id,effective_from,created_by,updated_by) values(?,?,?,?,?,?)",
                UUID.randomUUID(), student.id(), slot, today.minusDays(10), actor, actor);

        var matching = service.list(null, "초등 A반", null, null, null, null, null, 0, 20, "studentName,asc");
        var nonMatching = service.list(null, "중등 B반", null, null, null, null, null, 0, 20, "studentName,asc");

        assertThat(matching.items()).extracting("id").contains(student.id());
        assertThat(matching.applied()).containsEntry("className", "초등 A반");
        assertThat(nonMatching.items()).extracting("id").doesNotContain(student.id());
    }

    private static StudentCreate createCommand(String name, String phone) {
        return new StudentCreate(name, LocalDate.now().minusYears(8), "별빛학교", LocalDate.now(), false,
                List.of(new GuardianWrite(null, "김보호", "MOTHER", null, phone, null,
                        "SMS", true, 0)));
    }

    private UUID actor() { return jdbc.queryForObject("select id from admin_user order by created_at limit 1", UUID.class); }
    private static StudentService.RequestMetadata metadata(String requestId) {
        return new StudentService.RequestMetadata(requestId, "127.0.0.1", "integration-test");
    }
    private static void createRole(Statement statement, String role) throws SQLException {
        try (var result = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + role + "')")) {
            result.next();
            if (!result.getBoolean(1)) statement.execute("create role " + role);
        }
    }
    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("supabase"))) return current;
        if (Files.isDirectory(current.getParent().resolve("supabase"))) return current.getParent();
        throw new IllegalStateException("supabase directory was not found");
    }
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static String jdbcUrl() {
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException exception) { throw new IllegalStateException(exception); }
    }
}
