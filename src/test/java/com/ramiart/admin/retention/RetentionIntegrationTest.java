package com.ramiart.admin.retention;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ramiart.admin.auth.application.AdminReauthenticationService;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.consent.application.ConsentEvidenceStorage;
import com.ramiart.admin.datatransfer.application.DataTransferStorage;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.retention.application.RetentionService;
import com.ramiart.admin.retention.application.RetentionModels.*;
import com.ramiart.admin.retention.infrastructure.JdbcRetentionRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes=RetentionIntegrationTest.Config.class, webEnvironment=SpringBootTest.WebEnvironment.NONE)
class RetentionIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-09T00:00:00Z");
    static final EmbeddedPostgres PG=start();
    @Autowired DataSource dataSource;
    @Autowired com.ramiart.admin.retention.application.RetentionRepository repository;
    @Autowired com.ramiart.admin.auth.application.AuditRecorder audit;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired RetentionService service;
    @Autowired AdminReauthenticationService reauth;
    @Autowired DataTransferStorage transferStorage;
    JdbcTemplate jdbc;
    Authentication owner;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({RetentionService.class, JdbcRetentionRepository.class, JdbcAuditRecorder.class})
    static class Config {
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean AdminReauthenticationService reauth(){return mock(AdminReauthenticationService.class);}
        @Bean DataTransferStorage transferStorage(){return mock(DataTransferStorage.class);}
        @Bean ConsentEvidenceStorage evidenceStorage(){return mock(ConsentEvidenceStorage.class);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
    }
    @BeforeEach void reset() throws Exception {
        org.mockito.Mockito.reset(reauth,transferStorage);jdbc=new JdbcTemplate(dataSource);
        try(var c=dataSource.getConnection();var s=c.createStatement()){
            for(String role:List.of("anon","authenticated"))s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='"+role+"') then create role "+role+"; end if; end $$");
            s.execute("drop schema if exists public cascade");s.execute("drop schema if exists extensions cascade");s.execute("create schema public");s.execute("create schema extensions");
        }
        Path root=Path.of("").toAbsolutePath().normalize();
        if(!Files.isDirectory(root.resolve("supabase")))root=root.getParent();
        try(var files=Files.list(root.resolve("supabase/migrations"))){for(Path p:files.filter(p->p.getFileName().toString().matches("[0-9]+_.*\\.sql")).sorted().toList())PostgresScriptRunner.execute(dataSource,p);}
        PostgresScriptRunner.execute(dataSource,root.resolve("supabase/seed.sql"));
        UUID actor=jdbc.queryForObject("select u.id from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id where r.code='OWNER' limit 1",UUID.class);
        owner=new UsernamePasswordAuthenticationToken(actor.toString(),null,List.of(new SimpleGrantedAuthority("RETENTION_READ"),new SimpleGrantedAuthority("RETENTION_EXECUTE")));
    }
    @AfterAll static void close() throws Exception {PG.close();}
    @Test void MGT_RETENTION_hold_blocks_purge_and_replay_does_not_consume_reauthentication_twice(){
        UUID held=inquiry(), eligible=inquiry();
        service.createHold(new HoldWrite("INQUIRY",held,"Legal dispute preservation",null),owner,meta());
        Preview p=service.preview(new PreviewWrite("INQUIRY",NOW),owner);
        assertThat(p.candidateCount()).isEqualTo(2);assertThat(p.holdExcludedCount()).isEqualTo(1);
        UUID key=UUID.randomUUID();RunWrite request=new RunWrite(p.previewVersion(),"reauth-token","파기 실행");
        Run queued=service.execute(request,key,"session",owner,meta());
        service.processNext();
        Run completed=service.run(queued.id(),owner);
        assertThat(completed.status()).isEqualTo("COMPLETED");assertThat(completed.processedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inquiry where id=?",Integer.class,held)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inquiry where id=?",Integer.class,eligible)).isZero();
        assertThat(service.execute(request,key,"session",owner,meta()).id()).isEqualTo(queued.id());
        verify(reauth,times(1)).consume("reauth-token","session","RETENTION_EXECUTION",owner);
    }
    @Test void MGT_RETENTION_preview_drift_and_expiry_block_execution(){
        UUID id=inquiry();Preview p=service.preview(new PreviewWrite("INQUIRY",NOW),owner);
        service.createHold(new HoldWrite("INQUIRY",id,"Legal dispute preservation",null),owner,meta());
        assertThatThrownBy(()->service.execute(new RunWrite(p.previewVersion(),"token","파기 실행"),UUID.randomUUID(),"session",owner,meta())).hasMessage("RETENTION_PREVIEW_STALE");
        verifyNoInteractions(reauth);
    }
    @Test void MGT_RETENTION_preview_expires_after_twenty_four_hours(){
        Preview p=service.preview(new PreviewWrite("INQUIRY",NOW),owner);
        var later=new RetentionService(repository,reauth,audit,Clock.fixed(NOW.plus(Duration.ofHours(25)),ZoneOffset.UTC));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->later.execute(new RunWrite(p.previewVersion(),"token","파기 실행"),UUID.randomUUID(),"session",owner,meta())))
                .hasMessage("RETENTION_PREVIEW_STALE");verifyNoInteractions(reauth);
    }
    @Test void MGT_RETENTION_read_permission_cannot_mutate_or_execute(){
        Authentication read=new UsernamePasswordAuthenticationToken(owner.getName(),null,List.of(new SimpleGrantedAuthority("RETENTION_READ")));
        assertThatThrownBy(()->service.createHold(new HoldWrite("INQUIRY",inquiry(),"Legal dispute preservation",null),read,meta())).hasMessage("RETENTION_EXECUTE_DENIED");
        assertThatThrownBy(()->service.preview(new PreviewWrite("INQUIRY",NOW.plusSeconds(1)),read)).hasMessage("VALIDATION_ERROR");
    }
    @Test void MGT_RETENTION_changes_after_approval_require_a_new_preview(){
        UUID id=inquiry();Preview p=service.preview(new PreviewWrite("INQUIRY",NOW),owner);
        Run queued=service.execute(new RunWrite(p.previewVersion(),"token","파기 실행"),UUID.randomUUID(),"session",owner,meta());
        service.createHold(new HoldWrite("INQUIRY",id,"Legal dispute preservation",null),owner,meta());
        service.processNext();assertThat(service.run(queued.id(),owner).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from inquiry where id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void MGT_RETENTION_all_domain_queries_match_the_canonical_schema(){
        for(String domain:List.of("STUDENT_PRIVATE","CONSENT_EVIDENCE","TRANSFER_FILE","NOTIFICATION_PAYLOAD"))
            assertThat(service.preview(new PreviewWrite(domain,NOW),owner).domain()).isEqualTo(domain);
    }
    @Test void MGT_RETENTION_hold_release_and_expiry_restore_eligibility(){
        UUID id=inquiry();Hold hold=service.createHold(new HoldWrite("INQUIRY",id,"Legal dispute preservation",null),owner,meta());
        assertThat(service.releaseHold(hold.id(),"Legal review completed",owner,meta()).status()).isEqualTo("RELEASED");
        assertThat(service.preview(new PreviewWrite("INQUIRY",NOW),owner).holdExcludedCount()).isZero();
        jdbc.update("insert into retention_hold(id,target_type,target_id,reason,starts_at,ends_at,created_by) values(?,'INQUIRY',?,'Legal dispute preservation',?,?,?)",UUID.randomUUID(),id,Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW.minusSeconds(1)),UUID.fromString(owner.getName()));
        assertThat(service.holds("INQUIRY","EXPIRED",null,50,owner).items()).hasSize(1);
        assertThat(service.preview(new PreviewWrite("INQUIRY",NOW),owner).holdExcludedCount()).isZero();
    }
    @Test void MGT_RETENTION_database_owner_is_required_even_with_execute_permission(){
        Preview p=service.preview(new PreviewWrite("INQUIRY",NOW),owner);
        Authentication outsider=new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),null,owner.getAuthorities());
        assertThatThrownBy(()->service.execute(new RunWrite(p.previewVersion(),"token","파기 실행"),UUID.randomUUID(),"session",outsider,meta())).hasMessage("RETENTION_OWNER_REAUTH_REQUIRED");
        verifyNoInteractions(reauth);
    }
    @Test void MGT_RETENTION_worker_continues_batches_without_double_counting(){
        for(int i=0;i<101;i++)inquiry();Preview p=service.preview(new PreviewWrite("INQUIRY",NOW),owner);
        Run queued=service.execute(new RunWrite(p.previewVersion(),"token","파기 실행"),UUID.randomUUID(),"session",owner,meta());
        service.processNext();assertThat(service.run(queued.id(),owner).processedCount()).isEqualTo(100);
        assertThat(service.run(queued.id(),owner).status()).isEqualTo("PROCESSING");
        service.processNext();assertThat(service.run(queued.id(),owner).processedCount()).isEqualTo(101);
        assertThat(service.run(queued.id(),owner).status()).isEqualTo("COMPLETED");assertThat(service.processNext()).isFalse();
    }
    @Test void MGT_RETENTION_storage_failure_is_retryable_without_double_counting(){
        UUID id=UUID.randomUUID();UUID actor=UUID.fromString(owner.getName());
        jdbc.update("insert into data_transfer_job(id,direction,domain,status,template_version,source_file_name,storage_key,sha256,file_size,expires_at,created_by,created_at) values(?,'IMPORT','STUDENT','READY','STUDENT_V1','import.csv','data-transfers/test.csv',repeat('a',64),1,?,?,?)",id,Timestamp.from(NOW.minusSeconds(1)),actor,Timestamp.from(NOW.minusSeconds(60)));
        Preview p=service.preview(new PreviewWrite("TRANSFER_FILE",NOW),owner);RunWrite request=new RunWrite(p.previewVersion(),"token","파기 실행");
        Run queued=service.execute(request,UUID.randomUUID(),"session",owner,meta());
        doThrow(new IllegalStateException("unavailable")).when(transferStorage).delete(anyString());
        service.processNext();Run failed=service.run(queued.id(),owner);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.processedCount()).isZero();
        org.mockito.Mockito.reset(transferStorage);service.execute(request,UUID.randomUUID(),"session",owner,meta());
        service.processNext();assertThat(service.run(queued.id(),owner).processedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from data_transfer_job where id=?",String.class,id)).isEqualTo("EXPIRED");
    }
    @Test void MGT_RETENTION_student_purge_erases_contacts_and_preserves_identity(){
        UUID actor=UUID.fromString(owner.getName()), student=UUID.randomUUID(), guardian=UUID.randomUUID();
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,status,created_by,updated_by,created_at) values(?,'Private name','privatename','2010-01-01','DROPPED',?,?,?)",student,actor,actor,Timestamp.from(NOW.minusSeconds(60)));
        jdbc.update("insert into student_status_history(id,student_id,from_status,to_status,effective_date,reason,changed_by,student_version) values(?,?,'ACTIVE','DROPPED','2020-01-01','Course completed',?,0)",UUID.randomUUID(),student,actor);
        jdbc.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4) values(?,?,'Private contact','MOTHER',decode('01','hex'),repeat('a',64),'1234')",guardian,student);
        Run run=approve("STUDENT_PRIVATE");service.processNext();
        assertThat(service.run(run.id(),owner).processedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select private_purged_at is not null and student_name is null from student where id=?",Boolean.class,student)).isTrue();
        assertThat(jdbc.queryForObject("select name is null and phone_ciphertext is null and phone_hash is null from guardian_contact where id=?",Boolean.class,guardian)).isTrue();
    }
    @Test void MGT_RETENTION_notification_purge_erases_message_and_batch_payload(){
        UUID actor=UUID.fromString(owner.getName()), batch=UUID.randomUUID(), message=UUID.randomUUID();
        Timestamp old=Timestamp.from(NOW.minus(Duration.ofDays(800)));
        jdbc.update("insert into notification_batch(id,type,channel,body_template_ciphertext,scheduled_at,created_by,created_at) values(?,'GENERAL','MANUAL',decode('01','hex'),?,?,?)",batch,old,actor,old);
        jdbc.update("insert into notification_message(id,batch_key,type,channel,body_ciphertext,status,last_error_code,idempotency_scope,created_by,created_at) values(?,?,'GENERAL','MANUAL',decode('01','hex'),'FAILED','FAILED_PERMANENT',repeat('a',64),?,?)",message,batch,actor,old);
        Run run=approve("NOTIFICATION_PAYLOAD");service.processNext();
        assertThat(service.run(run.id(),owner).processedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload_purged_at is not null and body_ciphertext is null from notification_message where id=?",Boolean.class,message)).isTrue();
        assertThat(jdbc.queryForObject("select payload_purged_at is not null and body_template_ciphertext is null from notification_batch where id=?",Boolean.class,batch)).isTrue();
    }
    @Test void MGT_RETENTION_revoked_consent_evidence_is_deleted_after_five_years(){
        UUID actor=UUID.fromString(owner.getName()), student=UUID.randomUUID(), guardian=UUID.randomUUID(), policy=UUID.randomUUID(), asset=UUID.randomUUID(), consent=UUID.randomUUID();
        Timestamp old=Timestamp.from(NOW.minus(Duration.ofDays(2500)));
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?,'Test','test','2010-01-01',?,?)",student,actor,actor);
        jdbc.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4) values(?,?,'Contact','MOTHER',decode('01','hex'),repeat('a',64),'1234')",guardian,student);
        jdbc.update("insert into consent_policy(id,type,revision,status,title,body,evidence_required,created_by,published_by,published_at) values(?,'PORTRAIT',999,'ARCHIVED','Policy','Policy body',true,?,?,?)",policy,actor,actor,old);
        jdbc.update("insert into media_asset(id,storage_key,public_path,original_file_name,sha256,mime_type,file_size,width,height,status,created_by) values(?,'private-evidence/test.png','/private-evidence/test.png','test.png',repeat('a',64),'image/png',10,2,2,'READY',?)",asset,actor);
        jdbc.update("insert into student_consent(id,student_id,consent_policy_id,policy_type,guardian_contact_id,method,status,consented_at,evidence_asset_id,revoked_at,revoke_reason,created_by,revoked_by,created_at) values(?,?,?,'PORTRAIT',?,'PAPER','REVOKED',?,?,?,'Consent revoked',?,?,?)",consent,student,policy,guardian,old,asset,old,actor,actor,old);
        Run run=approve("CONSENT_EVIDENCE");service.processNext();
        assertThat(service.run(run.id(),owner).processedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select evidence_asset_id is null and evidence_purged_at is not null from student_consent where id=?",Boolean.class,consent)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from media_asset where id=?",Integer.class,asset)).isZero();
    }
    Run approve(String domain){Preview p=service.preview(new PreviewWrite(domain,NOW),owner);return service.execute(new RunWrite(p.previewVersion(),"token","파기 실행"),UUID.randomUUID(),"session",owner,meta());}
    UUID inquiry(){
        UUID id=UUID.randomUUID();jdbc.update("insert into inquiry(id,name_ciphertext,name_hash,phone_ciphertext,phone_hash,phone_last4,message_ciphertext,status,consent_policy_version,received_at,retention_expires_at) values(?,decode('01','hex'),repeat('b',64),decode('02','hex'),repeat('a',64),'1234',decode('03','hex'),'RECEIVED','v1',?,?)",id,Timestamp.from(NOW.minus(Duration.ofDays(1500))),Timestamp.from(NOW.minusSeconds(1)));return id;
    }
    Metadata meta(){return new Metadata("req_retention_test","127.0.0.1","test");}
    static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
