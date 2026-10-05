package com.ramiart.admin.lessonlog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.lessonlog.application.LessonLogModels.*;
import com.ramiart.admin.lessonlog.application.LessonLogService;
import com.ramiart.admin.lessonlog.application.LessonLogService.LessonLogException;
import com.ramiart.admin.student.infrastructure.AesGcmStudentDataProtector;
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
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LessonLogPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES=startPostgres();
    private final DataSource dataSource=POSTGRES.getPostgresDatabase();
    private JdbcTemplate jdbc;
    private LessonLogService service;
    private UUID actorId;
    private TestingAuthenticationToken auth;
    private final RequestMetadata metadata=new RequestMetadata("req_lesson_log_test","127.0.0.1","integration-test");

    @BeforeAll void resetDatabase() throws Exception {
        jdbc=new JdbcTemplate(dataSource);
        try(Connection connection=dataSource.getConnection();Statement statement=connection.createStatement()) {
            createRoleIfMissing(statement,"anon");createRoleIfMissing(statement,"authenticated");
            statement.execute("drop schema if exists public cascade");statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");statement.execute("create schema extensions");
        }
        Path root=projectRoot();
        try(var migrations=Files.list(root.resolve("supabase/migrations"))) {
            for(Path migration:migrations.filter(path->path.toString().endsWith(".sql")).sorted().toList()) PostgresScriptRunner.execute(dataSource,migration);
        }
        PostgresScriptRunner.execute(dataSource,root.resolve("supabase/seed.sql"));
        actorId=jdbc.queryForObject("select id from admin_user where email='owner@rami.local'",UUID.class);
        auth=new TestingAuthenticationToken(actorId.toString(),"","LESSON_LOG_READ","LESSON_LOG_WRITE");
        JdbcClient client=JdbcClient.create(dataSource);
        service=new LessonLogService(new JdbcLessonLogRepository(client,new ObjectMapper()),
                new JdbcAuditRecorder(client,new ObjectMapper()),
                new AesGcmStudentDataProtector("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
    }

    @AfterAll void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test void draftsAreEncryptedFinalizedIdempotentlyAndAmendmentsPreserveHistory() {
        Fixture f=fixture();
        View empty=service.get(f.sessionId(),auth);
        assertThat(empty.currentLog()).isNull();
        View created=service.createDraft(f.sessionId(),auth,metadata);
        assertThat(created.currentLog().status()).isEqualTo("DRAFT");
        assertThat(created.currentLog().studentRecords()).hasSize(2);

        SaveWrite write=new SaveWrite(0,null,"색을 섞어 풍경 그리기",List.of("색 혼합","풍경 구성"),List.of("수채 물감"),"계획과 다른 수업 진행",
                "수업 전체 메모",List.of(
                new StudentRecordWrite(f.presentStudentId(),"PRESENT","HIGH","진도 메모", "관찰 메모",null,List.of()),
                new StudentRecordWrite(f.absentStudentId(),"ABSENT",null,null,null,"결석 메모",List.of())));
        View saved=service.save(created.currentLog().id(),write,auth,metadata);
        assertThat(saved.currentLog().version()).isEqualTo(1);
        assertThat(saved.currentLog().studentRecords()).filteredOn(r->r.studentId().equals(f.presentStudentId()))
                .singleElement().satisfies(r->{assertThat(r.progressNote()).isEqualTo("진도 메모");assertThat(r.observation()).isEqualTo("관찰 메모");});
        byte[] encrypted=jdbc.queryForObject("select progress_note_ciphertext from student_lesson_record where student_id=?",byte[].class,f.presentStudentId());
        assertThat(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("진도 메모");
        assertThat(saved.finalizable()).isTrue();

        int finalizedAuditCount=jdbc.queryForObject("select count(*) from audit_log where action='LESSON_LOG_FINALIZED'",Integer.class);
        UUID key=UUID.randomUUID();
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        View replay=tx.execute(status->{
            View finalized=service.finalizeLog(created.currentLog().id(),new FinalizeWrite(1),key,auth,metadata);
            assertThat(finalized.currentLog().status()).isEqualTo("FINALIZED");
            return service.finalizeLog(created.currentLog().id(),new FinalizeWrite(1),key,auth,metadata);
        });
        assertThat(replay.currentLog().status()).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='LESSON_LOG_FINALIZED'",Integer.class)).isEqualTo(finalizedAuditCount+1);

        View amendment=service.amend(created.currentLog().id(),new AmendmentWrite("기록 내용 정정 요청"),auth,metadata);
        assertThat(amendment.currentLog().revision()).isEqualTo(2);
        assertThat(amendment.currentLog().status()).isEqualTo("DRAFT");
        assertThat(amendment.currentLog().studentRecords()).filteredOn(r->r.studentId().equals(f.presentStudentId()))
                .singleElement().satisfies(r->assertThat(r.progressNote()).isEqualTo("진도 메모"));
        assertThat(amendment.currentLog().overallNote()).isEqualTo("수업 전체 메모");
        assertThat(amendment.currentLog().changeReason()).isEqualTo("계획과 다른 수업 진행");
        assertThat(jdbc.queryForObject("select status from lesson_log where id=?",String.class,created.currentLog().id())).isEqualTo("AMENDED");
        View replayAfterAmendment=tx.execute(status->service.finalizeLog(created.currentLog().id(),new FinalizeWrite(1),key,auth,metadata));
        assertThat(replayAfterAmendment.currentLog().id()).isEqualTo(created.currentLog().id());
        assertThat(replayAfterAmendment.currentLog().status()).isEqualTo("AMENDED");
    }

    @Test void missingAttendanceStatusesAreReportedWithoutNullFailure() {
        Fixture f=fixture();
        jdbc.update("delete from student_attendance where attendance_session_id=?",f.sessionId());
        View view=service.get(f.sessionId(),auth);
        assertThat(view.finalizable()).isFalse();
        assertThat(view.missingRequiredStudentIds()).isEmpty();
        assertThatThrownBy(()->service.createDraft(f.sessionId(),auth,metadata))
                .isInstanceOf(LessonLogException.class).extracting("code").isEqualTo("LESSON_LOG_ATTENDANCE_INCOMPLETE");
    }

    @Test void saveRejectsStudentRecordsWithUnknownAttendanceState() {
        Fixture f=fixture(); View draft=service.createDraft(f.sessionId(),auth,metadata);
        jdbc.update("delete from student_attendance where attendance_session_id=? and student_id=?",f.sessionId(),f.presentStudentId());
        SaveWrite write=new SaveWrite(0,null,"수업 기록",List.of("활동"),List.of(),null,null,List.of(
                new StudentRecordWrite(f.presentStudentId(),null,"NORMAL",null,null,null,List.of()),
                new StudentRecordWrite(f.absentStudentId(),"ABSENT",null,null,null,null,List.of())));
        assertThatThrownBy(()->service.save(draft.currentLog().id(),write,auth,metadata))
                .isInstanceOf(LessonLogException.class).extracting("code").isEqualTo("LESSON_LOG_ATTENDANCE_INCOMPLETE");
    }

    @Test void creatingAnotherDraftAfterFinalizationIsRejected() {
        Fixture f=fixture(); View draft=service.createDraft(f.sessionId(),auth,metadata);
        SaveWrite write=new SaveWrite(0,null,"수업 기록",List.of("활동"),List.of(),null,null,List.of(
                new StudentRecordWrite(f.presentStudentId(),"PRESENT","NORMAL",null,null,null,List.of()),
                new StudentRecordWrite(f.absentStudentId(),"ABSENT",null,null,null,null,List.of())));
        service.save(draft.currentLog().id(),write,auth,metadata);
        service.finalizeLog(draft.currentLog().id(),new FinalizeWrite(1),UUID.randomUUID(),auth,metadata);
        assertThatThrownBy(()->service.createDraft(f.sessionId(),auth,metadata))
                .isInstanceOf(LessonLogException.class).extracting("code").isEqualTo("LESSON_LOG_FINALIZED");
    }

    @Test void finalizeRejectsAttendanceChangesSinceDraftWasCreated() {
        Fixture f=fixture(); View draft=service.createDraft(f.sessionId(),auth,metadata);
        SaveWrite write=new SaveWrite(0,null,"수업 기록",List.of("활동"),List.of(),null,null,List.of(
                new StudentRecordWrite(f.presentStudentId(),"PRESENT","NORMAL",null,null,null,List.of()),
                new StudentRecordWrite(f.absentStudentId(),"ABSENT",null,null,null,null,List.of())));
        service.save(draft.currentLog().id(),write,auth,metadata);
        jdbc.update("delete from student_attendance where attendance_session_id=? and student_id=?",f.sessionId(),f.presentStudentId());
        assertThatThrownBy(()->new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status->
                service.finalizeLog(draft.currentLog().id(),new FinalizeWrite(1),UUID.randomUUID(),auth,metadata)))
                .isInstanceOf(LessonLogException.class).extracting("code").isEqualTo("LESSON_LOG_ATTENDANCE_CHANGED");
        assertThat(jdbc.queryForObject("select status from lesson_log where id=?",String.class,draft.currentLog().id())).isEqualTo("DRAFT");
    }

    @Test void saveRejectsStudentsOutsideAttendanceSnapshotWithoutChangingDraft() {
        Fixture f=fixture(); View draft=service.createDraft(f.sessionId(),auth,metadata);
        SaveWrite write=new SaveWrite(0,null,"수업 기록",List.of("활동"),List.of(),null,null,List.of(
                new StudentRecordWrite(UUID.randomUUID(),"PRESENT","NORMAL",null,null,null,List.of()),
                new StudentRecordWrite(f.absentStudentId(),"ABSENT",null,null,null,null,List.of())));
        assertThatThrownBy(()->service.save(draft.currentLog().id(),write,auth,metadata))
                .isInstanceOf(LessonLogException.class).extracting("code").isEqualTo("LESSON_LOG_STUDENT_NOT_TARGET");
        assertThat(jdbc.queryForObject("select version from lesson_log where id=?",Long.class,draft.currentLog().id())).isZero();
    }

    private Fixture fixture() {
        UUID course=UUID.randomUUID(),group=UUID.randomUUID(),slot=UUID.randomUUID(),schedule=UUID.randomUUID(),item=UUID.randomUUID();
        UUID present=UUID.randomUUID(),absent=UUID.randomUUID(),session=UUID.randomUUID();
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        int order=Math.floorMod(course.hashCode(),100000);
        jdbc.update("insert into course(id,code,name,display_order,created_by,updated_by) values(?,?,?,?,?,?)",course,"TEST_"+course.toString().substring(0,8).toUpperCase(),"통합 과정",order,actorId,actorId);
        jdbc.update("insert into class_group(id,course_id,code,name,room_code,capacity,makeup_valid_days,starts_on,status,created_by,updated_by) values(?,?,?,?,?,10,30,?,'ACTIVE',?,?)",
                group,course,"GROUP_"+group.toString().substring(0,8).toUpperCase(),"수업 기록 통합 반","ROOM_A",today.minusDays(2),actorId,actorId);
        jdbc.update("insert into schedule_slot(id,class_group_id,created_by) values(?,?,?)",slot,group,actorId);
        String month=jdbc.queryForObject("select '2099-'||lpad(m::text,2,'0') from generate_series(1,12) m where not exists(select 1 from monthly_schedule where year_month='2099-'||lpad(m::text,2,'0') and status='DRAFT') order by m limit 1",String.class);
        jdbc.update("insert into monthly_schedule(id,year_month,revision,status,created_by) values(?,?,1,'DRAFT',?)",schedule,month,actorId);
        jdbc.update("insert into monthly_schedule_item(id,schedule_slot_id,monthly_schedule_id,day_of_week,start_time,end_time,title,room_code) values(?,?,?,1,'10:00','11:00','테스트 수업','ROOM_A')",item,slot,schedule);
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?,?,?,?,?,?)",present,"출석 원생","출석원생",today.minusDays(10),actorId,actorId);
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?,?,?,?,?,?)",absent,"결석 원생","결석원생",today.minusDays(10),actorId,actorId);
        jdbc.update("insert into attendance_session(id,schedule_item_id,schedule_slot_id,class_group_id,attendance_date,class_name_snapshot,room_code_snapshot,starts_at,ends_at,target_count) values(?,?,?,?,?,?,?, ?,?,2)",
                session,item,slot,group,today,"수업 기록 통합 반","ROOM_A",today.atTime(LocalTime.of(10,0)).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),today.atTime(LocalTime.of(11,0)).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
        jdbc.update("insert into attendance_session_student(attendance_session_id,student_id,student_name_snapshot,display_order) values(?,?,?,0),(?,?,?,1)",session,present,"출석 원생",session,absent,"결석 원생");
        jdbc.update("insert into student_attendance(id,attendance_session_id,student_id,status,reason,checked_by,updated_by) values(?,?,?,'PRESENT',null,?,?),(?,?,?,'ABSENT','결석 처리',?,?)",
                UUID.randomUUID(),session,present,actorId,actorId,UUID.randomUUID(),session,absent,actorId,actorId);
        jdbc.update("update attendance_session set status='CLOSED',present_count=1,late_count=0,absent_count=1,excused_count=0,closed_by=?,closed_at=now() where id=?",actorId,session);
        return new Fixture(session,present,absent);
    }
    private record Fixture(UUID sessionId,UUID presentStudentId,UUID absentStudentId) {}
    private static EmbeddedPostgres startPostgres(){try{return EmbeddedPostgres.builder().start();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private static Path projectRoot(){Path p=Path.of("").toAbsolutePath().normalize();while(p!=null&&!Files.isDirectory(p.resolve("supabase/migrations")))p=p.getParent();if(p==null)throw new IllegalStateException("supabase migrations directory not found");return p;}
    private static void createRoleIfMissing(Statement s,String role)throws SQLException{try(var rows=s.executeQuery("select exists(select 1 from pg_roles where rolname='"+role+"')")){rows.next();if(!rows.getBoolean(1))s.execute("create role "+role);}}
}
