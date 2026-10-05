package com.ramiart.admin.lessonplan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.lessonplan.application.LessonPlanModels.ItemWrite;
import com.ramiart.admin.lessonplan.application.LessonPlanModels.PlanWrite;
import com.ramiart.admin.lessonplan.application.LessonPlanModels.PublishWrite;
import com.ramiart.admin.lessonplan.application.LessonPlanService;
import com.ramiart.admin.lessonplan.application.LessonPlanService.LessonPlanException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LessonPlanPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final AtomicInteger FIXTURE_MONTH_OFFSET = new AtomicInteger(2);
    private final DataSource dataSource = POSTGRES.getPostgresDatabase();
    private JdbcTemplate jdbc;
    private LessonPlanService service;
    private UUID ownerId;

    @BeforeAll
    void prepareDatabase() throws SQLException, IOException {
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
        ownerId = jdbc.queryForObject("select id from admin_user order by email limit 1", UUID.class);
        JdbcClient client = JdbcClient.create(dataSource);
        service = new LessonPlanService(new JdbcLessonPlanRepository(client, new ObjectMapper()),
                new JdbcAuditRecorder(client, new ObjectMapper()));
    }

    @AfterAll
    void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test
    void draftSavePreviewPublishAndReplayUseScheduleSnapshot() {
        ScheduleFixture fixture = scheduleFixture();
        var auth = ownerAuth();
        var metadata = new LessonPlanService.RequestMetadata("req_lesson_plan", "127.0.0.1", "integration-test");

        var created = service.createDraft(fixture.classGroupId(), fixture.month().toString(), auth, metadata);
        assertThat(created.draft()).isNotNull();
        assertThat(created.draft().basedOnPlanId()).isNull();
        assertThat(created.scheduleSnapshot().dates()).containsExactlyElementsOf(fixture.lessonDates());

        List<ItemWrite> items = new ArrayList<>();
        for (LocalDate date : fixture.lessonDates()) {
            items.add(new ItemWrite(UUID.randomUUID(), date, 1, "색과 형태", List.of("색의 대비를 이해한다"),
                    List.of("색종이 조형 활동"), List.of("색종이"), List.of("가위 준비"), null));
        }
        var saved = service.save(created.draft().id(), new PlanWrite(0, items), auth, metadata);
        assertThat(saved.draft().version()).isEqualTo(1);
        assertThatThrownBy(() -> service.save(created.draft().id(), new PlanWrite(0, items), auth, metadata))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_VERSION_CONFLICT");

        var preview = service.preview(fixture.classGroupId(), fixture.month().toString(), saved.draft().id(), null, auth);
        assertThat(preview.publishable()).isTrue();
        assertThat(preview.diff().missingPlanDates()).isEmpty();
        assertThat(preview.diff().orphanPlanItems()).isEmpty();

        UUID key = UUID.randomUUID();
        var published = service.publish(saved.draft().id(),
                new PublishWrite(1, "월 계획 확정", fixture.scheduleRevision()), auth, key, metadata);
        assertThat(published.published().status()).isEqualTo("PUBLISHED");
        assertThat(published.published().items()).hasSize(fixture.lessonDates().size());
        var replay = service.publish(saved.draft().id(),
                new PublishWrite(1, "월 계획 확정", fixture.scheduleRevision()), auth, key, metadata);
        assertThat(replay.published().id()).isEqualTo(published.published().id());
        var nextDraft = service.createDraft(fixture.classGroupId(), fixture.month().toString(), auth, metadata);
        assertThat(nextDraft.draft().basedOnPlanId()).isEqualTo(published.published().id());
        assertThat(nextDraft.draft().items()).hasSize(fixture.lessonDates().size());
        var nextPublication = service.publish(nextDraft.draft().id(),
                new PublishWrite(0, "변경 revision 확정", fixture.scheduleRevision()), auth, UUID.randomUUID(), metadata);
        assertThat(nextPublication.published().status()).isEqualTo("PUBLISHED");
        var archivedReplay = service.publish(saved.draft().id(),
                new PublishWrite(1, "월 계획 확정", fixture.scheduleRevision()), auth, key, metadata);
        assertThat(archivedReplay.published().status()).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> service.save(saved.draft().id(), new PlanWrite(2, items), auth, metadata))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_PUBLISHED_IMMUTABLE");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='LESSON_PLAN_PUBLISHED'", Integer.class)).isEqualTo(2);
    }

    @Test
    void publishingRejectsChangedScheduleRevisionAndLeavesDraftOpen() {
        ScheduleFixture fixture = scheduleFixture();
        var auth = ownerAuth();
        var metadata = new LessonPlanService.RequestMetadata("req_lesson_plan_schedule_change", "127.0.0.1", "integration-test");
        var created = service.createDraft(fixture.classGroupId(), fixture.month().toString(), auth, metadata);
        List<ItemWrite> items = fixture.lessonDates().stream().map(date -> new ItemWrite(UUID.randomUUID(), date, 1,
                "색과 형태", List.of("색의 대비를 이해한다"), List.of("색종이 조형 활동"), List.of(), List.of(), null)).toList();
        var saved = service.save(created.draft().id(), new PlanWrite(0, items), auth, metadata);
        jdbc.update("update monthly_schedule set status='ARCHIVED' where year_month=? and status='PUBLISHED'", fixture.month().toString());
        UUID replacementScheduleId = UUID.randomUUID();
        jdbc.update("insert into monthly_schedule(id,year_month,revision,status,created_by) values(?,?,8,'DRAFT',?)",
                replacementScheduleId, fixture.month().toString(), ownerId);
        jdbc.update("update monthly_schedule set status='PUBLISHED',published_by=?,published_at=statement_timestamp() where id=?",
                ownerId, replacementScheduleId);

        assertThatThrownBy(() -> service.publish(saved.draft().id(),
                new PublishWrite(1, "월 계획 확정", fixture.scheduleRevision()), auth, UUID.randomUUID(), metadata))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_SCHEDULE_CHANGED");
        assertThat(service.get(fixture.classGroupId(), fixture.month().toString(), auth).draft().status()).isEqualTo("DRAFT");
    }

    @Test
    void lessonPlanRequiresReadPermissionAndRejectsOutOfMonthItems() {
        ScheduleFixture fixture = scheduleFixture();
        var noPermission = new TestingAuthenticationToken(ownerId.toString(), "", "STUDENT_READ");
        assertThatThrownBy(() -> service.get(fixture.classGroupId(), fixture.month().toString(), noPermission))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_READ_DENIED");
        var outOfScope = new TestingAuthenticationToken(UUID.randomUUID().toString(), "", "LESSON_PLAN_READ");
        assertThatThrownBy(() -> service.get(fixture.classGroupId(), fixture.month().toString(), outOfScope))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_SCOPE_DENIED");

        var auth = ownerAuth();
        var metadata = new LessonPlanService.RequestMetadata("req_lesson_plan_validation", "127.0.0.1", "integration-test");
        var created = service.createDraft(fixture.classGroupId(), fixture.month().toString(), auth, metadata);
        ItemWrite incomplete = new ItemWrite(UUID.randomUUID(), fixture.lessonDates().getFirst(), 1,
                " ", List.of(), List.of("활동"), List.of(), List.of(), null);
        var preview = service.preview(fixture.classGroupId(), fixture.month().toString(), null, List.of(incomplete), auth);
        assertThat(preview.publishable()).isFalse();
        assertThat(preview.diff().invalidItems()).hasSize(1);
        assertThat(preview.diff().invalidItems().getFirst().fields()).contains("title", "objectives");
        ItemWrite outsideMonth = new ItemWrite(UUID.randomUUID(), fixture.month().plusMonths(1).atDay(1), 1,
                "잘못된 날짜", List.of("목표"), List.of("활동"), List.of(), List.of(), null);
        assertThatThrownBy(() -> service.save(created.draft().id(), new PlanWrite(0, List.of(outsideMonth)), auth, metadata))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_DATE_INVALID");
        LocalDate unscheduledDate = fixture.month().atDay(1).datesUntil(fixture.month().atEndOfMonth().plusDays(1))
                .filter(date -> !fixture.lessonDates().contains(date)).findFirst().orElseThrow();
        ItemWrite unscheduled = new ItemWrite(UUID.randomUUID(), unscheduledDate, 1, "비정기 계획",
                List.of("목표"), List.of("활동"), List.of(), List.of(), null);
        var saved = service.save(created.draft().id(), new PlanWrite(0, List.of(unscheduled)), auth, metadata);
        var diffPreview = service.preview(fixture.classGroupId(), fixture.month().toString(), saved.draft().id(), null, auth);
        assertThat(diffPreview.diff().orphanPlanItems()).containsExactly(unscheduledDate);
        assertThatThrownBy(() -> service.publish(saved.draft().id(),
                new PublishWrite(1, "잘못된 계획", fixture.scheduleRevision()), auth, UUID.randomUUID(), metadata))
                .isInstanceOf(LessonPlanException.class).extracting("code").isEqualTo("LESSON_PLAN_DATE_INVALID");
    }

    private TestingAuthenticationToken ownerAuth() {
        return new TestingAuthenticationToken(ownerId.toString(), "", "LESSON_PLAN_READ", "LESSON_PLAN_WRITE", "LESSON_PLAN_PUBLISH");
    }

    private ScheduleFixture scheduleFixture() {
        UUID courseId = UUID.randomUUID();
        UUID classGroupId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        YearMonth month = YearMonth.now(ZoneId.of("Asia/Seoul")).plusMonths(FIXTURE_MONTH_OFFSET.getAndIncrement());
        jdbc.update("insert into course(id,code,name,display_order,created_by,updated_by) values(?,?,?,?,?,?)",
                courseId, "PLAN_" + courseId.toString().substring(0, 8).toUpperCase(), "수업 계획 과정",
                Math.floorMod(courseId.hashCode(), 100000), ownerId, ownerId);
        jdbc.update("insert into class_group(id,course_id,code,name,room_code,capacity,makeup_valid_days,starts_on,status,created_by,updated_by) values(?,?,?,?,?,10,30,?,'ACTIVE',?,?)",
                classGroupId, courseId, "PLAN_GROUP_" + classGroupId.toString().substring(0, 8).toUpperCase(),
                "계획 검증 반", "ROOM_A", month.atDay(1), ownerId, ownerId);
        jdbc.update("insert into schedule_slot(id,class_group_id,created_by) values(?,?,?)", slotId, classGroupId, ownerId);
        jdbc.update("insert into monthly_schedule(id,year_month,revision,status,created_by) values(?,?,7,'DRAFT',?)",
                scheduleId, month.toString(), ownerId);
        int dayOfWeek = month.atDay(1).getDayOfWeek().getValue();
        jdbc.update("insert into monthly_schedule_item(id,schedule_slot_id,monthly_schedule_id,day_of_week,start_time,end_time,title,room_code) values(?,?,?,?,'10:00','11:00','정기 수업','ROOM_A')",
                UUID.randomUUID(), slotId, scheduleId, dayOfWeek);
        jdbc.update("update monthly_schedule set status='PUBLISHED',published_by=?,published_at=statement_timestamp() where id=?",
                ownerId, scheduleId);
        List<LocalDate> lessonDates = month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                .filter(date -> date.getDayOfWeek().getValue() == dayOfWeek).toList();
        return new ScheduleFixture(classGroupId, month, 7, lessonDates);
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
    private record ScheduleFixture(UUID classGroupId, YearMonth month, int scheduleRevision, List<LocalDate> lessonDates) {}
}
