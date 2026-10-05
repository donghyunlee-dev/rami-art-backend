package com.ramiart.admin.attendance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.attendance.application.AttendanceService;
import com.ramiart.admin.attendance.application.AttendanceService.AttendanceException;
import com.ramiart.admin.attendance.application.AttendanceModels.AttendanceWrite;
import com.ramiart.admin.attendance.application.AttendanceModels.CloseWrite;
import com.ramiart.admin.attendance.application.AttendanceService.RequestMetadata;
import com.ramiart.admin.makeup.application.MakeupRepository;
import com.ramiart.admin.makeup.application.MakeupService;
import com.ramiart.admin.makeup.infrastructure.JdbcMakeupRepository;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendancePersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    DataSource dataSource = POSTGRES.getPostgresDatabase();
    AttendanceService attendanceService;
    MakeupService makeupService;
    JdbcTemplate jdbc;
    UUID actorId;
    TransactionTemplate transaction;

    @BeforeAll
    void resetDatabase() throws SQLException, IOException {
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
        JdbcClient client = JdbcClient.create(dataSource);
        attendanceService = new AttendanceService(new JdbcAttendanceRepository(client), new JdbcAuditRecorder(client, new ObjectMapper()));
        makeupService = new MakeupService(new JdbcMakeupRepository(client), new JdbcAuditRecorder(client, new ObjectMapper()), org.mockito.Mockito.mock(com.ramiart.admin.auth.application.AdminReauthenticationService.class));
    }

    @AfterAll
    void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test
    void attendanceReadRequiresScopeAndReturnsOnlyTheRequestedDatabaseSession() {
        UUID sessionId = insertAttendanceFixture("요청한 반");
        UUID otherSessionId = insertAttendanceFixture("다른 반");
        var auth = new TestingAuthenticationToken(actorId.toString(), "", "ATTENDANCE_READ");

        var result = attendanceService.findById(sessionId, auth);

        assertThat(result.id()).isEqualTo(sessionId);
        assertThat(result.className()).isEqualTo("요청한 반");
        assertThat(result.students()).hasSize(1);
        assertThat(result.students().getFirst().studentName()).isEqualTo("통합 검증 원생");
        assertThat(result.id()).isNotEqualTo(otherSessionId);
        var unauthorized = new TestingAuthenticationToken(actorId.toString(), "", "STUDENT_READ");
        assertThatThrownBy(() -> attendanceService.findById(sessionId, unauthorized))
                .isInstanceOf(AttendanceException.class)
                .extracting("code").isEqualTo("ATTENDANCE_READ_DENIED");
        assertThatThrownBy(() -> attendanceService.findById(UUID.randomUUID(), auth))
                .isInstanceOf(AttendanceException.class)
                .extracting("code").isEqualTo("ATTENDANCE_SESSION_NOT_FOUND");

        UUID studentId = jdbc.queryForObject("select student_id from attendance_session_student where attendance_session_id=?", UUID.class, sessionId);
        var metadata = new RequestMetadata("req_attendance_write", "127.0.0.1", "integration-test");
        var writeAuth = new TestingAuthenticationToken(actorId.toString(), "", "ATTENDANCE_WRITE");
        var closeAuth = new TestingAuthenticationToken(actorId.toString(), "", "ATTENDANCE_CLOSE");
        var saved = attendanceService.save(sessionId, studentId,
                new AttendanceWrite("PRESENT", null, null, false, null, 0), writeAuth, metadata);
        assertThat(saved.sessionVersion()).isEqualTo(1);
        assertThat(saved.attendance().status()).isEqualTo("PRESENT");
        UUID closeKey = UUID.randomUUID();
        var closeResult = attendanceService.close(sessionId, new CloseWrite(1), closeAuth, closeKey, metadata);
        assertThat(closeResult.created()).isTrue();
        var closed = closeResult.session();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.summary().presentCount()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action in ('ATTENDANCE_RESULT_SAVED','ATTENDANCE_SESSION_CLOSED')", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> attendanceService.save(sessionId, studentId,
                new AttendanceWrite("PRESENT", null, null, false, 0L, 2), writeAuth, metadata))
                .isInstanceOf(AttendanceException.class)
                .extracting("code").isEqualTo("ATTENDANCE_SESSION_CLOSED");
        var replay = attendanceService.close(sessionId, new CloseWrite(1), closeAuth, closeKey, metadata);
        assertThat(replay.created()).isFalse();
        assertThat(replay.session().status()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ATTENDANCE_SESSION_CLOSED'", Integer.class)).isOne();

        UUID makeupStudentId = jdbc.queryForObject("select student_id from attendance_session_student where attendance_session_id=?", UUID.class, otherSessionId);
        var makeupSaved = attendanceService.save(otherSessionId, makeupStudentId,
                new AttendanceWrite("ABSENT", null, "보강 처리 통합 검증", true, null, 0), writeAuth, metadata);
        assertThat(makeupSaved.sessionVersion()).isEqualTo(1);
        UUID makeupCloseKey = UUID.randomUUID();
        var makeupResult = attendanceService.close(otherSessionId, new CloseWrite(1), closeAuth, makeupCloseKey, metadata);
        assertThat(makeupResult.created()).isTrue();
        var makeupClosure = makeupResult.session();
        assertThat(makeupClosure.createdMakeupCount()).isOne();
        assertThat(makeupClosure.makeupCaseIds()).hasSize(1);
        var makeupReplay = attendanceService.close(otherSessionId, new CloseWrite(1), closeAuth, makeupCloseKey, metadata);
        assertThat(makeupReplay.created()).isFalse();
        assertThat(makeupReplay.session().makeupCaseIds()).containsExactlyElementsOf(makeupClosure.makeupCaseIds());
        assertThatThrownBy(() -> attendanceService.close(otherSessionId, new CloseWrite(0), closeAuth, makeupCloseKey, metadata))
                .isInstanceOf(AttendanceException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("select count(*) from makeup_case where origin_session_id=?", Integer.class, otherSessionId)).isOne();
    }

    @Test
    void attendanceDateReadRequiresPermissionAndRejectsUnknownFilters() {
        UUID sessionId=insertAttendanceFixture("권한 확인 반");
        var readOnlyStudent=new TestingAuthenticationToken(actorId.toString(),"","STUDENT_READ");
        assertThatThrownBy(()->attendanceService.find(LocalDate.now(ZoneId.of("Asia/Seoul")),"ALL",readOnlyStudent))
                .isInstanceOf(AttendanceException.class).extracting("code").isEqualTo("ATTENDANCE_READ_DENIED");
        var reader=new TestingAuthenticationToken(actorId.toString(),"","ATTENDANCE_READ");
        assertThatThrownBy(()->attendanceService.find(LocalDate.now(ZoneId.of("Asia/Seoul")),"UNKNOWN",reader))
                .isInstanceOf(AttendanceException.class).extracting("code").isEqualTo("VALIDATION_ERROR");
        assertThat(attendanceService.find(LocalDate.now(ZoneId.of("Asia/Seoul")),"ALL",reader).sessions())
                .extracting(session->session.id()).contains(sessionId);
    }

    @Test
    void closingIncompleteAttendanceReturnsStudentDetails() {
        UUID sessionId = insertAttendanceFixture("미완료 마감 검증 반");
        var closeAuth = new TestingAuthenticationToken(actorId.toString(), "", "ATTENDANCE_CLOSE");
        var metadata = new RequestMetadata("req_attendance_incomplete", "127.0.0.1", "integration-test");

        assertThatThrownBy(() -> attendanceService.close(sessionId, new CloseWrite(0), closeAuth, UUID.randomUUID(), metadata))
                .isInstanceOf(AttendanceException.class)
                .satisfies(exception -> {
                    AttendanceException attendanceException = (AttendanceException) exception;
                    assertThat(attendanceException.code()).isEqualTo("ATTENDANCE_INCOMPLETE");
                    assertThat(attendanceException.details()).containsKey("students");
                    assertThat((java.util.List<?>) attendanceException.details().get("students")).hasSize(1);
                    var student = (java.util.Map<?, ?>) ((java.util.List<?>) attendanceException.details().get("students")).getFirst();
                    assertThat(student.get("studentId")).isNotNull();
                    assertThat(student.get("studentName")).isEqualTo("통합 검증 원생");
                    assertThat(student.get("missingFields")).isEqualTo(java.util.List.of("status"));
                });
    }

    @Test
    void makeupReservationAndCancellationKeepAttendanceSnapshotAndCapacityConsistent() {
        UUID originSession = insertAttendanceFixture("보강 원본 반");
        UUID studentId = jdbc.queryForObject("select student_id from attendance_session_student where attendance_session_id=?", UUID.class, originSession);
        var writeAuth = new TestingAuthenticationToken(actorId.toString(), "", "ATTENDANCE_WRITE");
        var closeAuth = new TestingAuthenticationToken(actorId.toString(), "", "ATTENDANCE_CLOSE");
        var makeupAuth = new TestingAuthenticationToken(actorId.toString(), "", "MAKEUP_READ", "MAKEUP_WRITE");
        var metadata = new RequestMetadata("req_makeup_roundtrip", "127.0.0.1", "integration-test");
        attendanceService.save(originSession, studentId, new AttendanceWrite("ABSENT", null, "질병 결석", true, null, 0), writeAuth, metadata);
        attendanceService.close(originSession, new CloseWrite(1), closeAuth, UUID.randomUUID(), metadata);
        UUID caseId = jdbc.queryForObject("select id from makeup_case where origin_session_id=?", UUID.class, originSession);
        UUID slotId = jdbc.queryForObject("select schedule_slot_id from attendance_session where id=?", UUID.class, originSession);
        UUID itemId = jdbc.queryForObject("select schedule_item_id from attendance_session where id=?", UUID.class, originSession);
        UUID groupId = jdbc.queryForObject("select class_group_id from attendance_session where id=?", UUID.class, originSession);
        LocalDate futureDate = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(7);
        UUID targetSession = UUID.randomUUID();
        jdbc.update("insert into attendance_session(id,schedule_item_id,schedule_slot_id,class_group_id,attendance_date,class_name_snapshot,room_code_snapshot,starts_at,ends_at) values(?,?,?,?,?,?,?,?,?)",
                targetSession,itemId,slotId,groupId,futureDate,"보강 예약 반","ROOM_A",futureDate.atTime(10,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),futureDate.atTime(11,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());

        var candidates = makeupService.candidates(caseId, futureDate, futureDate, makeupAuth);
        assertThat(candidates.items()).extracting(item -> item.sessionId()).contains(targetSession);
        UUID reserveKey=UUID.randomUUID();
        var reservationBody=new com.ramiart.admin.makeup.application.MakeupModels.Reservation(targetSession, 0, 0);
        var reserved = transaction.execute(status -> makeupService.reserve(caseId, reservationBody, reserveKey, makeupAuth,
                new MakeupService.Metadata("req_makeup_reserve", "127.0.0.1", "integration-test")));
        var replayed=transaction.execute(status->makeupService.reserve(caseId,reservationBody,reserveKey,makeupAuth,
                new MakeupService.Metadata("req_makeup_reserve_replay","127.0.0.1","integration-test")));
        assertThat(reserved.detail().makeupCase().status()).isEqualTo("RESERVED");
        assertThat(replayed.created()).isFalse();
        assertThat(jdbc.queryForObject("select target_count from attendance_session where id=?", Integer.class, targetSession)).isOne();

        var cancelled = transaction.execute(status -> makeupService.cancel(caseId, new com.ramiart.admin.makeup.application.MakeupModels.VersionedReason(1, "일정 변경"), UUID.randomUUID(), makeupAuth,
                new MakeupService.Metadata("req_makeup_cancel", "127.0.0.1", "integration-test")));
        assertThat(cancelled.makeupCase().status()).isEqualTo("AVAILABLE");
        assertThat(cancelled.history()).extracting(attempt -> attempt.status()).contains("CANCELLED");
        assertThat(jdbc.queryForObject("select target_count from attendance_session where id=?", Integer.class, targetSession)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from attendance_session_student where attendance_session_id=? and makeup_case_id=?", Integer.class, targetSession, caseId)).isZero();

        transaction.executeWithoutResult(status -> makeupService.reserve(caseId, new com.ramiart.admin.makeup.application.MakeupModels.Reservation(targetSession, 2, 2), UUID.randomUUID(), makeupAuth,
                new MakeupService.Metadata("req_makeup_reserve_again", "127.0.0.1", "integration-test")));
        var substitute = attendanceService.save(targetSession, studentId,
                new AttendanceWrite("PRESENT", null, null, false, null, 3), writeAuth, metadata);
        assertThat(substitute.sessionVersion()).isEqualTo(4);
        attendanceService.close(targetSession, new CloseWrite(4), closeAuth, UUID.randomUUID(), metadata);
        assertThat(jdbc.queryForObject("select status from makeup_case where id=?", String.class, caseId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select completed_attendance_id from makeup_case where id=?", UUID.class, caseId)).isNotNull();
    }

    @Test
    void makeupExpiryIsIdempotentAndOnlyExpiresAvailableCases() {
        UUID sessionId=insertAttendanceFixture("보강 만료 반");
        UUID studentId=jdbc.queryForObject("select student_id from attendance_session_student where attendance_session_id=?",UUID.class,sessionId);
        var writeAuth=new TestingAuthenticationToken(actorId.toString(),"","ATTENDANCE_WRITE");
        var closeAuth=new TestingAuthenticationToken(actorId.toString(),"","ATTENDANCE_CLOSE");
        var metadata=new RequestMetadata("req_makeup_expiry","127.0.0.1","integration-test");
        attendanceService.save(sessionId,studentId,new AttendanceWrite("ABSENT",null,"미출석",true,null,0),writeAuth,metadata);
        attendanceService.close(sessionId,new CloseWrite(1),closeAuth,UUID.randomUUID(),metadata);
        UUID caseId=jdbc.queryForObject("select id from makeup_case where origin_session_id=?",UUID.class,sessionId);
        jdbc.update("update makeup_case set expires_on=? where id=?",LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1),caseId);

        int expired=transaction.execute(status->makeupService.expireAvailableCases(LocalDate.now(ZoneId.of("Asia/Seoul"))));

        assertThat(expired).isOne();
        assertThat(jdbc.queryForObject("select status from makeup_case where id=?",String.class,caseId)).isEqualTo("EXPIRED");
        Integer secondRun=transaction.execute(status->makeupService.expireAvailableCases(LocalDate.now(ZoneId.of("Asia/Seoul"))));
        assertThat(secondRun.intValue()).isEqualTo(0);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='MAKEUP_CASE_EXPIRED' and target_id=?",Integer.class,caseId)).isOne();
    }

    private UUID insertAttendanceFixture(String className) {
        UUID courseId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int fixtureOrder = Math.floorMod(courseId.hashCode(), 100000);
        jdbc.update("insert into course(id,code,name,display_order,created_by,updated_by) values(?,?,?,?,?,?)",
                courseId, "TEST_" + courseId.toString().substring(0, 8).toUpperCase(), "검증 과정", fixtureOrder, actorId, actorId);
        jdbc.update("insert into class_group(id,course_id,code,name,room_code,capacity,makeup_valid_days,starts_on,status,created_by,updated_by) values(?,?,?,?,?,10,30,?,'ACTIVE',?,?)",
                groupId, courseId, "GROUP_" + groupId.toString().substring(0, 8).toUpperCase(), className,
                "ROOM_A", today.minusDays(1), actorId, actorId);
        jdbc.update("insert into schedule_slot(id,class_group_id,created_by) values(?,?,?)", slotId, groupId, actorId);
        String yearMonth = jdbc.queryForObject("""
                select '2099-' || lpad(month::text, 2, '0')
                from generate_series(1, 12) as month
                where not exists (
                    select 1 from monthly_schedule where year_month = '2099-' || lpad(month::text, 2, '0') and status = 'DRAFT'
                )
                order by month
                limit 1
                """, String.class);
        jdbc.update("insert into monthly_schedule(id,year_month,revision,status,created_by) values(?,?,1,'DRAFT',?)",
                scheduleId, yearMonth, actorId);
        jdbc.update("insert into monthly_schedule_item(id,schedule_slot_id,monthly_schedule_id,day_of_week,start_time,end_time,title,room_code) values(?,?,?,1,'10:00','11:00',?,'ROOM_A')",
                itemId, slotId, scheduleId, className);
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?,?,?, ?,?,?)",
                studentId, "통합 검증 원생", "통합검증원생", today.minusDays(3), actorId, actorId);
        jdbc.update("insert into attendance_session(id,schedule_item_id,schedule_slot_id,class_group_id,attendance_date,class_name_snapshot,room_code_snapshot,starts_at,ends_at,target_count) values(?,?,?,?,?,?,?,?,?,1)",
                sessionId, itemId, slotId, groupId, today,
                className, "ROOM_A", today.atTime(LocalTime.of(10, 0)).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
                today.atTime(LocalTime.of(11, 0)).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
        jdbc.update("insert into attendance_session_student(attendance_session_id,student_id,student_name_snapshot,display_order) values(?,?,?,0)",
                sessionId, studentId, "통합 검증 원생");
        return sessionId;
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("supabase/migrations"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("supabase migrations directory not found");
        return current;
    }
    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var rows = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + role + "')")) {
            rows.next();
            if (!rows.getBoolean(1)) statement.execute("create role " + role);
        }
    }
}
