package com.ramiart.admin.retention;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.datatransfer.application.*;
import com.ramiart.admin.datatransfer.infrastructure.JdbcDataTransferRepository;
import com.ramiart.admin.inquiry.infrastructure.AesGcmInquiryDataProtector;
import com.ramiart.admin.dev.PostgresScriptRunner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes=TransferQueueIntegrationTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class TransferQueueIntegrationTest {
    static final EmbeddedPostgres PG=start();
    @Autowired DataSource dataSource;
    @Autowired DataTransferService service;
    @Autowired DataTransferStorage storage;
    JdbcTemplate jdbc;Authentication auth;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({DataTransferService.class,JdbcDataTransferRepository.class,AesGcmInquiryDataProtector.class,JdbcAuditRecorder.class})
    static class Config {
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean DataTransferStorage storage(){return mock(DataTransferStorage.class);}
        @Bean DataTransferStudentWorker students(){return mock(DataTransferStudentWorker.class);}
        @Bean DataTransferDomainWorker domains(){return mock(DataTransferDomainWorker.class);}
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");}
    @BeforeEach void setup()throws Exception{
        org.mockito.Mockito.reset(storage);jdbc=new JdbcTemplate(dataSource);
        try(var c=dataSource.getConnection();var s=c.createStatement()){
            for(String role:List.of("anon","authenticated"))s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='"+role+"') then create role "+role+"; end if; end $$");
            s.execute("drop schema if exists public cascade");s.execute("drop schema if exists extensions cascade");s.execute("create schema public");s.execute("create schema extensions");
        }
        Path root=Path.of("").toAbsolutePath().normalize();
        if(!Files.isDirectory(root.resolve("supabase")))root=root.getParent();try(var files=Files.list(root.resolve("supabase/migrations"))){for(Path p:files.filter(p->p.getFileName().toString().matches("[0-9]+_.*\\.sql")).sorted().toList())PostgresScriptRunner.execute(dataSource,p);}
        PostgresScriptRunner.execute(dataSource,root.resolve("supabase/seed.sql"));
        UUID actor=jdbc.queryForObject("select id from admin_user order by created_at limit 1",UUID.class);
        auth=new UsernamePasswordAuthenticationToken(actor.toString(),null,List.of(new SimpleGrantedAuthority("DATA_TRANSFER_IMPORT"),new SimpleGrantedAuthority("DATA_TRANSFER_EXPORT")));
    }
    @AfterAll static void close()throws Exception{PG.close();}
    @Test void MGT_TRANSFER_import_is_durable_and_parses_once_in_worker(){
        var job=service.upload("STUDENT","STUDENT_V1",new MockMultipartFile("file","students.csv","text/csv",(service.template("STUDENT",auth).csv()+"Child,,2026-01-01,Guardian,MOTHER,+821012345678,,,\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),auth);
        assertThat(job.status()).isEqualTo("PARSING");
        assertThat(jdbc.queryForObject("select count(*) from data_transfer_row where job_id=?",Integer.class,job.id())).isZero();
        assertThat(service.processNext()).isTrue();assertThat(service.processNext()).isFalse();
        assertThat(service.job(job.id(),auth).status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select payload_ciphertext is null from data_transfer_work where job_id=?",Boolean.class,job.id())).isTrue();
        assertThat(service.rows(job.id(),null,null,50,auth).items()).hasSize(1);
        assertThat(jdbc.queryForObject("select status from data_transfer_row where job_id=?",String.class,job.id())).isEqualTo("VALID");
    }
    @Test void MGT_TRANSFER_invalid_csv_fails_in_worker_and_clears_queue_payload(){
        var job=service.upload("STUDENT","STUDENT_V1",new MockMultipartFile("file","students.csv","text/csv","wrong,headers\r\nsecret,data\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),auth);
        assertThat(job.status()).isEqualTo("PARSING");service.processNext();
        assertThat(service.job(job.id(),auth).status()).isEqualTo("FAILED");
        assertThat(service.job(job.id(),auth).errorCode()).isEqualTo("TRANSFER_FILE_INVALID");
        assertThat(jdbc.queryForObject("select payload_ciphertext is null from data_transfer_work where job_id=?",Boolean.class,job.id())).isTrue();
    }
    @Test void MGT_TRANSFER_export_queues_approved_snapshot_and_replays(){
        var request=new DataTransferService.ExportRequest("STUDENT","ANONYMIZED",Map.of(),"Compliance export",null);
        var preview=service.previewExport(request,auth);UUID key=UUID.randomUUID();
        var approved=new DataTransferService.ExportRequest("STUDENT","ANONYMIZED",Map.of(),"Compliance export",preview.previewToken());
        var job=service.createExport(approved,key,auth);assertThat(job.status()).isEqualTo("PROCESSING");assertThat(job.downloadable()).isFalse();
        verifyNoInteractions(storage);assertThat(service.createExport(approved,key,auth).id()).isEqualTo(job.id());
        service.processNext();assertThat(service.job(job.id(),auth).status()).isEqualTo("COMPLETED");
        verify(storage,times(1)).upload(anyString(),any());
    }
    @Test void MGT_TRANSFER_storage_failure_retains_queue_and_expiry_respects_hold(){
        var request=new DataTransferService.ExportRequest("STUDENT","ANONYMIZED",Map.of(),"Compliance export",null);
        var preview=service.previewExport(request,auth);var job=service.createExport(new DataTransferService.ExportRequest("STUDENT","ANONYMIZED",Map.of(),"Compliance export",preview.previewToken()),UUID.randomUUID(),auth);
        doThrow(new IllegalStateException("Storage unavailable")).when(storage).upload(anyString(),any());
        service.processNext();assertThat(jdbc.queryForObject("select state from data_transfer_work where job_id=?",String.class,job.id())).isEqualTo("QUEUED");
        UUID actor=UUID.fromString(auth.getName());jdbc.update("update data_transfer_job set expires_at=statement_timestamp()-interval '1 second' where id=?",job.id());
        jdbc.update("insert into retention_hold(id,target_type,target_id,reason,created_by) values(?,'DATA_TRANSFER_JOB',?,'Legal hold preservation',?)",UUID.randomUUID(),job.id(),actor);
        assertThat(service.cleanupExpired()).isZero();verify(storage,never()).delete(anyString());
        jdbc.update("update retention_hold set status='EXPIRED' where target_id=?",job.id());
        assertThat(service.cleanupExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload_ciphertext is null from data_transfer_work where job_id=?",Boolean.class,job.id())).isTrue();
    }
    static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
