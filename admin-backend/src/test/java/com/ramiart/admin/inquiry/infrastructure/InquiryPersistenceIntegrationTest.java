package com.ramiart.admin.inquiry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.inquiry.application.InquiryException;
import com.ramiart.admin.inquiry.application.InquiryModels.*;
import com.ramiart.admin.inquiry.application.InquiryRateLimiter;
import com.ramiart.admin.inquiry.application.InquiryService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes=InquiryPersistenceIntegrationTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InquiryPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES=start();
    @Autowired DataSource dataSource;
    @Autowired InquiryService service;
    JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({InquiryService.class,InquiryRateLimiter.class,JdbcInquiryRepository.class,AesGcmInquiryDataProtector.class,JdbcAuditRecorder.class})
    static class Config { @Bean java.time.Clock clock(){return java.time.Clock.systemUTC();} }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",InquiryPersistenceIntegrationTest::url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("admin.security.inquiry-data-key",()->"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");r.add("admin.inquiry.consent-policy-version",()->"privacy-2026-07");
    }
    @BeforeEach void reset()throws Exception{jdbc=new JdbcTemplate(dataSource);try(Connection c=dataSource.getConnection();Statement s=c.createStatement()){
        role(s,"anon");role(s,"authenticated");s.execute("drop schema if exists public cascade");s.execute("drop schema if exists extensions cascade");s.execute("create schema public");s.execute("create schema extensions");}
        try(var files=Files.list(root().resolve("supabase/migrations"))){for(Path p:files.filter(v->v.toString().endsWith(".sql")).sorted().toList())PostgresScriptRunner.execute(dataSource,p);}PostgresScriptRunner.execute(dataSource,root().resolve("supabase/seed.sql"));}
    @AfterAll void close()throws IOException{POSTGRES.close();}

    @Test void publicSubmissionEncryptsAndIsIdempotentWhileHoneypotDoesNotPersist(){UUID key=UUID.randomUUID();var command=submission("김보호","010-1234-5678","");
        assertThat(service.submit(command,key,meta("submit"))).isEqualTo(new Accepted(true));service.submit(command,key,meta("replay"));
        assertThat(jdbc.queryForObject("select count(*) from inquiry",Integer.class)).isOne();byte[] name=jdbc.queryForObject("select name_ciphertext from inquiry",byte[].class);
        assertThat(new String(name,StandardCharsets.UTF_8)).doesNotContain("김보호");assertThat(jdbc.queryForObject("select phone_last4 from inquiry",String.class)).isEqualTo("5678");
        assertThat(jdbc.queryForObject("select details::text from audit_log where action='INQUIRY_SUBMITTED'",String.class)).doesNotContain("김보호").doesNotContain("5678");
        service.submit(submission("광고봇","010-9999-0000","filled"),UUID.randomUUID(),meta("honeypot"));assertThat(jdbc.queryForObject("select count(*) from inquiry",Integer.class)).isOne();
        assertThatThrownBy(()->service.submit(submission("다른 이름","010-1234-5678",""),key,meta("reuse"))).isInstanceOf(InquiryException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");}

    @Test void publicSubmissionRejectsInvalidConsentCourseAndCountsHoneypotForRateLimit(){
        assertThatThrownBy(()->service.submit(new PublicSubmission("김보호","010-1234-5678",null,"문의합니다",false,"privacy-2026-07",""),UUID.randomUUID(),meta("consent")))
                .isInstanceOf(InquiryException.class).extracting("code").isEqualTo("PRIVACY_CONSENT_REQUIRED");
        assertThatThrownBy(()->service.submit(new PublicSubmission("김보호","010-1234-5678",UUID.randomUUID(),"문의합니다",true,"privacy-2026-07",""),UUID.randomUUID(),meta("course")))
                .isInstanceOf(InquiryException.class).extracting("code").isEqualTo("INQUIRY_INVALID");
        for(int i=0;i<5;i++)service.submit(submission("광고봇","010-9999-0000","filled"),UUID.randomUUID(),meta("honeypot-"+i));
        assertThatThrownBy(()->service.submit(submission("광고봇","010-9999-0000","filled"),UUID.randomUUID(),meta("honeypot-limit")))
                .isInstanceOf(InquiryException.class).extracting("code").isEqualTo("INQUIRY_RATE_LIMITED");
        assertThat(jdbc.queryForObject("select count(*) from inquiry",Integer.class)).isZero();
    }

    @Test void listMasksPhoneButDetailRevealsAndGetHasNoReadSideEffect(){service.submit(submission("김보호","010-1234-5678",""),UUID.randomUUID(),meta("submit"));
        var page=service.list("김보호",List.of(),List.of("RECEIVED"),LocalDate.now().minusDays(1),LocalDate.now(),"ALL",0,20);
        assertThat(page.items()).singleElement().satisfies(v->{assertThat(v.maskedPhone()).isEqualTo("***-****-5678");assertThat(v.name()).isEqualTo("김보호");});UUID id=page.items().getFirst().inquiryId();
        var detail=service.detail(id);assertThat(detail.phone()).isEqualTo("+821012345678");assertThat(detail.message()).isEqualTo("수업 가능 시간을 문의합니다.");assertThat(detail.read()).isFalse();
        assertThat(jdbc.queryForObject("select read_at is null from inquiry where id=?",Boolean.class,id)).isTrue();}

    @Test void listUsesExactHashesAndRejectsUnknownCourseFilters(){service.submit(submission("김 보호","010-1234-5678",""),UUID.randomUUID(),meta("submit"));
        LocalDate from=LocalDate.now().minusDays(1),to=LocalDate.now();
        assertThat(service.list("010-1234-5678",List.of(),List.of("RECEIVED"),from,to,"ALL",0,20).totalElements()).isOne();
        assertThat(service.list("1234",List.of(),List.of("RECEIVED"),from,to,"ALL",0,20).totalElements()).isZero();
        assertThat(service.list("김   보호",List.of(),List.of("RECEIVED"),from,to,"ALL",0,20).totalElements()).isOne();
        assertThatThrownBy(()->service.list(null,List.of(UUID.randomUUID()),List.of("RECEIVED"),from,to,"ALL",0,20))
                .isInstanceOf(InquiryException.class).extracting("code").isEqualTo("INQUIRY_QUERY_INVALID");
    }

    @Test void readAndActivitiesAreVersionedIdempotentAndAuditedWithoutNotes(){UUID actor=jdbc.queryForObject("select id from admin_user order by created_at limit 1",UUID.class);service.submit(submission("김보호","010-1234-5678",""),UUID.randomUUID(),meta("submit"));UUID id=jdbc.queryForObject("select id from inquiry",UUID.class);
        UUID readKey=UUID.randomUUID();var receipt=service.markRead(id,new ReadReceiptWrite(0),actor,readKey,meta("read"));assertThat(receipt.read()).isTrue();assertThat(receipt.version()).isOne();
        assertThat(service.markRead(id,new ReadReceiptWrite(0),actor,readKey,meta("read-retry"))).isEqualTo(receipt);
        assertThat(service.markRead(id,new ReadReceiptWrite(0),actor,UUID.randomUUID(),meta("read-after"))).isEqualTo(receipt);
        UUID activityKey=UUID.randomUUID();var created=service.addActivity(id,new ActivityWrite("CONTACTING","전화 연결을 시도함",1),actor,activityKey,meta("activity"));assertThat(created.inquiry().status()).isEqualTo("CONTACTING");
        assertThat(service.addActivity(id,new ActivityWrite("CONTACTING","전화 연결을 시도함",1),actor,activityKey,meta("activity-retry"))).isEqualTo(created);
        assertThat(service.detail(id).activities()).singleElement().extracting("note").isEqualTo("전화 연결을 시도함");
        assertThatThrownBy(()->service.addActivity(id,new ActivityWrite("COMPLETED","다른 관리자가 동시에 처리함",1),actor,UUID.randomUUID(),meta("stale")))
                .isInstanceOf(InquiryException.class).extracting("code").isEqualTo("INQUIRY_VERSION_CONFLICT");
        var completed=service.addActivity(id,new ActivityWrite("COMPLETED","상담을 완료했습니다",2),actor,UUID.randomUUID(),meta("complete"));
        assertThat(completed.inquiry().allowedTransitions()).isEmpty();
        assertThat(jdbc.queryForObject("select string_agg(details::text,'') from audit_log where action='INQUIRY_STATUS_CHANGED'",String.class)).doesNotContain("전화 연결을 시도함").doesNotContain("상담을 완료했습니다");
        assertThatThrownBy(()->service.addActivity(id,new ActivityWrite("CONTACTING","되돌리기 시도",3),actor,UUID.randomUUID(),meta("invalid"))).isInstanceOf(InquiryException.class).extracting("code").isEqualTo("INQUIRY_TRANSITION_DENIED");
        assertThat(jdbc.queryForObject("select count(*) from inquiry_activity",Integer.class)).isEqualTo(2);}

    private static PublicSubmission submission(String name,String phone,String company){return new PublicSubmission(name,phone,null,"수업 가능 시간을 문의합니다.",true,"privacy-2026-07",company);}
    private static InquiryService.RequestMetadata meta(String id){return new InquiryService.RequestMetadata(id,"127.0.0.1","integration-test");}
    private static void role(Statement s,String name)throws SQLException{try(var r=s.executeQuery("select exists(select 1 from pg_roles where rolname='"+name+"')")){r.next();if(!r.getBoolean(1))s.execute("create role "+name);}}
    private static Path root(){Path p=Path.of("").toAbsolutePath().normalize();if(Files.isDirectory(p.resolve("supabase")))return p;return p.getParent();}
    private static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private static String url(){try(Connection c=POSTGRES.getPostgresDatabase().getConnection()){return c.getMetaData().getURL();}catch(SQLException e){throw new IllegalStateException(e);}}
}
