package com.ramiart.admin.enrollment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.enrollment.api.EnrollmentController;
import com.ramiart.admin.enrollment.application.EnrollmentException;
import com.ramiart.admin.enrollment.application.EnrollmentModels.*;
import com.ramiart.admin.enrollment.application.EnrollmentService;
import com.ramiart.admin.inquiry.infrastructure.AesGcmInquiryDataProtector;
import com.ramiart.admin.student.infrastructure.AesGcmStudentDataProtector;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest(classes=EnrollmentPersistenceIntegrationTest.TestConfiguration.class,
        webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrollmentPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES=startPostgres();
    @Autowired DataSource dataSource;
    @Autowired EnrollmentService service;
    @Autowired AesGcmInquiryDataProtector inquiryProtector;
    @Autowired AesGcmStudentDataProtector studentProtector;
    private JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({EnrollmentService.class,JdbcEnrollmentRepository.class,AesGcmStudentDataProtector.class,
            AesGcmInquiryDataProtector.class,JdbcAuditRecorder.class})
    static class TestConfiguration {@Bean Clock clock(){return Clock.systemUTC();}}

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",EnrollmentPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"postgres");
        registry.add("admin.security.staff-phone-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("admin.security.inquiry-data-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }
    @BeforeEach void reset()throws Exception{jdbc=new JdbcTemplate(dataSource);try(Connection c=dataSource.getConnection();Statement s=c.createStatement()){role(s,"anon");role(s,"authenticated");s.execute("drop schema if exists public cascade");s.execute("drop schema if exists extensions cascade");s.execute("create schema public");s.execute("create schema extensions");}try(var files=Files.list(root().resolve("supabase/migrations"))){for(Path file:files.filter(p->p.toString().endsWith(".sql")).sorted().toList())PostgresScriptRunner.execute(dataSource,file);}PostgresScriptRunner.execute(dataSource,root().resolve("supabase/seed.sql"));}
    @AfterAll void close()throws IOException{POSTGRES.close();}

    @Test void manualCaseEnrollsAtomicallyAndReplaysWithoutDuplicates(){
        UUID actor=actor(),course=UUID.randomUUID(),group=UUID.randomUUID(),slot=UUID.randomUUID(),policy=UUID.randomUUID();fixture(actor,course,group,slot,policy,2);
        var created=service.create(new CaseCreate(null,"김상담","010-1234-5678",course,group),actor,UUID.randomUUID(),meta("create"));
        assertThat(created.status()).isEqualTo("NEW");assertThat(created.phone()).isEqualTo("+821012345678");
        assertThat(created.nextAction()).isEqualTo("CONTACT");
        EnrollWrite draft=enroll(created.version(),group,slot,policy,"010-1234-5678",null,null);
        var preview=service.preview(created.id(),draft);assertThat(preview.canEnroll()).isTrue();
        EnrollWrite command=enroll(created.version(),group,slot,policy,"010-1234-5678",null,preview.previewToken());UUID key=UUID.randomUUID();
        var enrolled=service.enroll(created.id(),command,actor,key,meta("enroll"));var replayed=service.enroll(created.id(),command,actor,key,meta("replay"));
        assertThat(enrolled.enrollmentCase().status()).isEqualTo("ENROLLED");assertThat(enrolled.enrollmentCase().nextAction()).isNull();assertThat(replayed.studentId()).isEqualTo(enrolled.studentId());
        assertThat(enrolled.guardianIds()).hasSize(1);assertThat(enrolled.scheduleAssignmentIds()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from student where id=?",Integer.class,enrolled.studentId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from student_consent where student_id=?",Integer.class,enrolled.studentId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ENROLLMENT_COMPLETED'",Integer.class)).isOne();
        assertThat(new String(jdbc.queryForObject("select phone_ciphertext from enrollment_case where id=?",byte[].class,created.id()),StandardCharsets.UTF_8)).doesNotContain("12345678");
        assertThatThrownBy(()->jdbc.update("delete from enrollment_activity where enrollment_case_id=?",created.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("enrollment activity is append only");
    }

    @Test void enrollmentCaseHasAssignableAdminOwner(){
        UUID actor=actor(),assignee=UUID.randomUUID(),course=UUID.randomUUID(),group=UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,display_name,created_by) values(?,?,'test','상담 담당자',?)",
                assignee,"assignee-"+assignee+"@example.test",actor);
        UUID operatorRole=jdbc.queryForObject("select id from admin_role where code='OPERATOR'",UUID.class);
        jdbc.update("insert into admin_user_role(admin_user_id,admin_role_id,assigned_by) values(?,?,?)",assignee,operatorRole,actor);
        fixture(actor,course,group,UUID.randomUUID(),UUID.randomUUID(),2);
        var created=service.create(new CaseCreate(null,"상담 문의","010-1234-5678",course,group),actor,UUID.randomUUID(),meta("assignee-create"));
        assertThat(created.assigneeId()).isEqualTo(actor);

        var changed=service.assign(created.id(),new AssigneeWrite(assignee,created.version()),actor,UUID.randomUUID(),meta("assignee-change"));

        assertThat(changed.assigneeId()).isEqualTo(assignee);
        assertThat(changed.assigneeName()).isEqualTo("상담 담당자");
        assertThat(changed.version()).isEqualTo(created.version()+1);
        assertThat(service.assignees()).anyMatch(value->value.id().equals(assignee));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ENROLLMENT_ASSIGNEE_CHANGED'",Integer.class)).isOne();
    }

    @Test void assignmentRejectsInactiveAdminAndStaleCaseVersion(){
        UUID actor=actor(),inactive=UUID.randomUUID(),replacement=UUID.randomUUID(),course=UUID.randomUUID(),group=UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,display_name,created_by) values(?,?,'test','비활성 관리자',?)",
                inactive,"inactive-"+inactive+"@example.test",actor);
        jdbc.update("insert into admin_user(id,email,password_hash,display_name,created_by) values(?,?,'test','새 담당자',?)",
                replacement,"replacement-"+replacement+"@example.test",actor);
        UUID operatorRole=jdbc.queryForObject("select id from admin_role where code='OPERATOR'",UUID.class);
        jdbc.update("insert into admin_user_role(admin_user_id,admin_role_id,assigned_by) values(?,?,?)",inactive,operatorRole,actor);
        jdbc.update("insert into admin_user_role(admin_user_id,admin_role_id,assigned_by) values(?,?,?)",replacement,operatorRole,actor);
        jdbc.update("update admin_user set status='INACTIVE',status_reason='test' where id=?",inactive);
        fixture(actor,course,group,UUID.randomUUID(),UUID.randomUUID(),2);
        var created=service.create(new CaseCreate(null,"상담 문의","010-1234-5678",course,group),actor,UUID.randomUUID(),meta("invalid-assignee-create"));

        assertThatThrownBy(()->service.assign(created.id(),new AssigneeWrite(inactive,created.version()),actor,UUID.randomUUID(),meta("inactive-assignee")))
                .isInstanceOf(EnrollmentException.class).extracting("code").isEqualTo("ENROLLMENT_ASSIGNEE_INVALID");

        var changed=service.assign(created.id(),new AssigneeWrite(replacement,created.version()),actor,UUID.randomUUID(),meta("assignee-version-update"));
        assertThatThrownBy(()->service.assign(created.id(),new AssigneeWrite(inactive,created.version()),actor,UUID.randomUUID(),meta("stale-assignee-version")))
                .isInstanceOf(EnrollmentException.class).extracting("code").isEqualTo("ENROLLMENT_VERSION_CONFLICT");
        assertThat(service.detail(created.id()).assigneeId()).isEqualTo(replacement);
        assertThat(service.detail(created.id()).version()).isEqualTo(changed.version());
    }

    @Test void assignmentRejectsTerminalCases(){
        UUID actor=actor(),course=UUID.randomUUID(),group=UUID.randomUUID();fixture(actor,course,group,UUID.randomUUID(),UUID.randomUUID(),2);
        var created=service.create(new CaseCreate(null,"상담 문의","010-1234-5678",course,group),actor,UUID.randomUUID(),meta("terminal-assignee-create"));
        var lost=service.lost(created.id(),new LostWrite("다른 기관 등록",created.version()),actor,UUID.randomUUID(),meta("terminal-assignee-lost"));

        assertThatThrownBy(()->service.assign(created.id(),new AssigneeWrite(actor,lost.version()),actor,UUID.randomUUID(),meta("terminal-assignee")))
                .isInstanceOf(EnrollmentException.class).extracting("code").isEqualTo("ENROLLMENT_INVALID_TRANSITION");
    }

    @Test void capacityConsentAndDuplicateChecksPreventPartialStudents(){
        UUID actor=actor(),course=UUID.randomUUID(),group=UUID.randomUUID(),slot=UUID.randomUUID(),policy=UUID.randomUUID();fixture(actor,course,group,slot,policy,1);
        var first=service.create(new CaseCreate(null,"첫 상담","010-1111-2222",course,group),actor,UUID.randomUUID(),meta("first"));
        var firstDraft=enroll(first.version(),group,slot,policy,"010-1111-2222",null,null);var firstPreview=service.preview(first.id(),firstDraft);
        service.enroll(first.id(),enroll(first.version(),group,slot,policy,"010-1111-2222",null,firstPreview.previewToken()),actor,UUID.randomUUID(),meta("first-enroll"));

        var second=service.create(new CaseCreate(null,"둘 상담","010-3333-4444",course,group),actor,UUID.randomUUID(),meta("second"));
        var noConsent=enroll(second.version(),group,slot,null,"010-3333-4444",null,null);var consentPreview=service.preview(second.id(),noConsent);
        assertThat(consentPreview.missingConsentIds()).containsExactly(policy);assertThat(consentPreview.canEnroll()).isFalse();
        var fullDraft=enroll(second.version(),group,slot,policy,"010-3333-4444",null,null);var fullPreview=service.preview(second.id(),fullDraft);
        assertThat(fullPreview.remainingSeats()).isZero();
        assertThatThrownBy(()->service.enroll(second.id(),enroll(second.version(),group,slot,policy,"010-3333-4444",null,fullPreview.previewToken()),actor,UUID.randomUUID(),meta("full")))
                .isInstanceOf(EnrollmentException.class).extracting("code").isEqualTo("ENROLLMENT_CAPACITY_FULL");
        assertThat(jdbc.queryForObject("select count(*) from student",Integer.class)).isOne();
    }

    @Test void duplicatePreviewReturnsEveryMatchReason(){
        UUID actor=actor(),course=UUID.randomUUID(),group=UUID.randomUUID(),slot=UUID.randomUUID(),policy=UUID.randomUUID();fixture(actor,course,group,slot,policy,3);
        var first=service.create(new CaseCreate(null,"첫 상담","010-1111-2222",course,group),actor,UUID.randomUUID(),meta("duplicate-first"));
        var firstDraft=enroll(first.version(),group,slot,policy,"010-1111-2222",null,null);
        var firstPreview=service.preview(first.id(),firstDraft);
        service.enroll(first.id(),enroll(first.version(),group,slot,policy,"010-1111-2222",null,firstPreview.previewToken()),actor,UUID.randomUUID(),meta("duplicate-first-enroll"));

        var second=service.create(new CaseCreate(null,"둘 상담","010-3333-4444",course,group),actor,UUID.randomUUID(),meta("duplicate-second"));
        var secondPreview=service.preview(second.id(),enroll(second.version(),group,slot,policy,"010-1111-2222",null,null));

        assertThat(secondPreview.duplicateCandidates()).hasSize(1);
        assertThat(secondPreview.duplicateCandidates().getFirst().matchedBy())
                .containsExactlyInAnyOrder("NAME_BIRTHDAY","GUARDIAN_PHONE");
    }

    @Test void contactDuplicateCandidatesMatchInquiryCaseAndStudentHashesAndMaskNames()throws Exception{
        UUID actor=actor(),course=UUID.randomUUID(),group=UUID.randomUUID(),slot=UUID.randomUUID(),policy=UUID.randomUUID();fixture(actor,course,group,slot,policy,3);
        String phone="+821012345678";
        var existingCase=service.create(new CaseCreate(null,"김가람",phone,course,group),actor,UUID.randomUUID(),meta("candidate-case"));

        UUID inquiryId=UUID.randomUUID();
        jdbc.update("""
                insert into inquiry(id,name_ciphertext,name_hash,phone_ciphertext,phone_hash,phone_last4,message_ciphertext,
                  status,consent_policy_version,retention_expires_at)
                values(?,?,?,?,?,?,?,'RECEIVED','v1',statement_timestamp()+interval '3 years')
                """,inquiryId,inquiryProtector.protect("박문의"),inquiryProtector.hash("name:박문의"),
                inquiryProtector.protect(phone),inquiryProtector.hash("phone:"+phone),"5678",
                inquiryProtector.protect("수업을 문의합니다."));

        UUID studentId=UUID.randomUUID(),guardianId=UUID.randomUUID();
        jdbc.update("insert into student(id,student_name,student_name_search,status,joined_at,created_by,updated_by) values(?,?,?,'ACTIVE',current_date,?,?)",
                studentId,"이원생","이원생",actor,actor);
        jdbc.update("""
                insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4,
                  preferred_channel,primary_contact,display_order)
                values(?,?,?,'MOTHER',?,?,?,'SMS',true,0)
                """,guardianId,studentId,"이보호자",studentProtector.protect(phone).ciphertext(),studentProtector.hash(phone),"5678");

        var candidates=service.duplicateCandidates("010-1234-5678");

        assertThat(candidates).extracting(ContactDuplicateCandidate::type)
                .containsExactlyInAnyOrder("INQUIRY","ENROLLMENT","STUDENT");
        assertThat(candidates).anySatisfy(candidate->{assertThat(candidate.id()).isEqualTo(inquiryId);assertThat(candidate.maskedName()).isEqualTo("박**");});
        assertThat(candidates).anySatisfy(candidate->{assertThat(candidate.id()).isEqualTo(existingCase.id());assertThat(candidate.maskedName()).isEqualTo("김**");});
        assertThat(candidates).anySatisfy(candidate->{assertThat(candidate.id()).isEqualTo(studentId);assertThat(candidate.maskedName()).isEqualTo("이**");});
        assertThat(candidates).allSatisfy(candidate->{assertThat(candidate.phoneLast4()).isEqualTo("5678");assertThat(candidate.matchedBy()).containsExactly("PHONE_HASH");});

        var mvc=MockMvcBuilders.standaloneSetup(new EnrollmentController(service)).addFilters(new RequestIdFilter()).build();
        String candidateResponse=mvc.perform(post("/api/admin/enrollments/duplicate-candidates")
                        .principal(authentication(actor,"ENROLLMENT_WRITE","INQUIRY_READ","ENROLLMENT_READ","STUDENT_READ"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(candidateResponse).doesNotContain(phone,"김가람","박문의","이원생");
        String body="{\"inquiryId\":\""+inquiryId+"\"}";
        var linkedResponse=mvc.perform(post("/api/admin/enrollments").header("Idempotency-Key",UUID.randomUUID().toString())
                        .header("X-Request-Id","req_inquiry_create").principal(authentication(actor,"ENROLLMENT_READ","ENROLLMENT_WRITE","INQUIRY_READ"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requestId").value("req_inquiry_create"))
                .andExpect(jsonPath("$.data.inquiryId").value(inquiryId.toString()))
                .andExpect(jsonPath("$.data.nextAction").value("CONTACT")).andReturn();
        UUID linkedId=UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(linkedResponse.getResponse().getContentAsString()).path("data").path("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from enrollment_case where inquiry_id=?",Integer.class,inquiryId)).isOne();
        mvc.perform(post("/api/admin/enrollments").header("Idempotency-Key",UUID.randomUUID().toString())
                        .principal(authentication(actor,"ENROLLMENT_READ","ENROLLMENT_WRITE","INQUIRY_READ"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ENROLLMENT_CASE_EXISTS"))
                .andExpect(jsonPath("$.error.details.caseId").value(linkedId.toString()));
    }

    @Test void defaultListIncludesNewlyCreatedCaseAndReturnsEmptyListWhenNoneExist() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new EnrollmentController(service))
                .addFilters(new RequestIdFilter()).build();
        assertThat(mvc.perform(get("/api/admin/enrollments?page=0&size=20"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"totalElements\":0");
        UUID actor=actor();
        UUID courseId=UUID.randomUUID();
        jdbc.update("insert into course(id,code,name,display_order,active,created_by,updated_by) values(?,?,'목록 조회 과정',1,true,?,?)",
                courseId,"LIST_"+courseId.toString().substring(0,8).toUpperCase(Locale.ROOT),actor,actor);
        CaseDetail created=service.create(new CaseCreate(null,"목록 상담","010-9876-5432",courseId,null),
                actor,UUID.randomUUID(),meta("list-create"));

        mvc.perform(get("/api/admin/enrollments?page=0&size=20").header("X-Request-Id","req_enrollment_list_data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req_enrollment_list_data"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(created.id().toString()))
                .andExpect(jsonPath("$.data.items[0].status").value("NEW"))
                .andExpect(jsonPath("$.data.items[0].phoneLast4").value("5432"));
        mvc.perform(get("/api/admin/enrollments?statuses=NEW&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/admin/enrollments?statuses=CONTACTED&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/admin/enrollments?courseId="+courseId+"&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(created.id().toString()));
        mvc.perform(get("/api/admin/enrollments?courseId="+UUID.randomUUID()+"&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private void fixture(UUID actor,UUID course,UUID group,UUID slot,UUID policy,int capacity){
        jdbc.update("insert into course(id,code,name,display_order,active,created_by,updated_by) values(?,?,'등록 과정',99,true,?,?)",course,"ENR_"+course.toString().substring(0,6).toUpperCase(),actor,actor);
        jdbc.update("insert into class_group(id,course_id,code,name,room_code,capacity,starts_on,status,created_by,updated_by) values(?, ?, ?, '등록반','ROOM_E',?,?,'ACTIVE',?,?)",group,course,"GRP_"+group.toString().substring(0,6).toUpperCase(),capacity,LocalDate.now(),actor,actor);
        jdbc.update("insert into schedule_slot(id,class_group_id,created_by) values(?,?,?)",slot,group,actor);
        jdbc.update("insert into consent_policy(id,type,revision,status,title,body,required,published_by,published_at,created_by) values(?,'PERSONAL_DATA_REQUIRED',99,'PUBLISHED','필수 동의','필수 개인정보 동의 본문',true,?,statement_timestamp(),?)",policy,actor,actor);
    }
    private static EnrollWrite enroll(long version,UUID group,UUID slot,UUID policy,String phone,String override,String token){return new EnrollWrite(version,new StudentWrite("신규 원생",LocalDate.now().minusYears(8),"별빛학교",LocalDate.now()),List.of(new GuardianWrite("김보호","MOTHER",null,phone,null,"SMS",true,0)),group,List.of(slot),LocalDate.now(),policy==null?List.of():List.of(policy),override,token);}
    private UUID actor(){return jdbc.queryForObject("select id from admin_user order by created_at limit 1",UUID.class);}
    private static org.springframework.security.core.Authentication authentication(UUID actor,String...permissions){
        return UsernamePasswordAuthenticationToken.authenticated(actor.toString(),"n/a",Arrays.stream(permissions)
                .<org.springframework.security.core.GrantedAuthority>map(permission->()->permission).toList());
    }
    private static EnrollmentService.RequestMetadata meta(String id){return new EnrollmentService.RequestMetadata(id,"127.0.0.1","integration-test");}
    private static void role(Statement s,String role)throws SQLException{try(var r=s.executeQuery("select exists(select 1 from pg_roles where rolname='"+role+"')")){r.next();if(!r.getBoolean(1))s.execute("create role "+role);}}
    private static Path root(){Path p=Path.of("").toAbsolutePath().normalize();if(Files.isDirectory(p.resolve("supabase")))return p;if(Files.isDirectory(p.getParent().resolve("supabase")))return p.getParent();throw new IllegalStateException("supabase directory not found");}
    private static EmbeddedPostgres startPostgres(){try{return EmbeddedPostgres.builder().start();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private static String jdbcUrl(){try(Connection c=POSTGRES.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(SQLException e){throw new IllegalStateException(e);}}
}
