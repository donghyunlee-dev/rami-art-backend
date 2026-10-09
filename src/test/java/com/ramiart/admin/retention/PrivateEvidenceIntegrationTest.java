package com.ramiart.admin.retention;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.consent.application.ConsentEvidenceStorage;
import com.ramiart.admin.consent.application.ConsentEvidenceService;
import com.ramiart.admin.consent.infrastructure.JdbcConsentRepository;
import com.ramiart.admin.media.infrastructure.JdbcMediaRepository;
import com.ramiart.admin.media.infrastructure.SecureImageValidator;
import com.ramiart.admin.media.application.MediaModels.RequestMetadata;
import com.ramiart.admin.dev.PostgresScriptRunner;
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

@SpringBootTest(classes=PrivateEvidenceIntegrationTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class PrivateEvidenceIntegrationTest {
    static final EmbeddedPostgres PG=start();
    @Autowired DataSource dataSource;
    @Autowired ConsentEvidenceService service;
    @Autowired JdbcConsentRepository consents;
    @Autowired ConsentEvidenceStorage storage;
    JdbcTemplate jdbc;Authentication auth;UUID student,other;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({ConsentEvidenceService.class,JdbcMediaRepository.class,JdbcConsentRepository.class,SecureImageValidator.class,JdbcAuditRecorder.class})
    static class Config {
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean ConsentEvidenceStorage storage(){return mock(ConsentEvidenceStorage.class);}
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
        auth=new UsernamePasswordAuthenticationToken(actor.toString(),null,List.of(new SimpleGrantedAuthority("CONSENT_WRITE")));
        student=createStudent(actor);other=createStudent(actor);
    }
    @AfterAll static void close()throws Exception{PG.close();}
    @Test void MGT_CONSENT_private_upload_replays_and_cannot_be_bound_to_another_student()throws Exception{
        byte[] image=image();UUID key=UUID.randomUUID();var asset=service.upload(student,image,"scan.png","image/png",key,auth,meta());
        assertThat(service.upload(student,image,"scan.png","image/png",key,auth,meta())).isEqualTo(asset);
        assertThat(consents.privateReadyEvidence(asset.id(),student)).isTrue();assertThat(consents.privateReadyEvidence(asset.id(),other)).isFalse();
        assertThat(jdbc.queryForObject("select storage_key from media_asset where id=?",String.class,asset.id())).startsWith("private-evidence/");
        verify(storage,times(1)).upload(anyString(),any(),eq("image/png"));
        assertThatThrownBy(()->service.upload(student,image(),"other.png","image/png",key,new UsernamePasswordAuthenticationToken(auth.getName(),null,List.of()),meta())).hasMessage("CONSENT_WRITE_DENIED");
    }
    @Test void MGT_CONSENT_storage_failure_does_not_create_asset()throws Exception{
        doThrow(new IllegalStateException("storage down")).when(storage).upload(anyString(),any(),anyString());
        long count=jdbc.queryForObject("select count(*) from consent_evidence_upload",Long.class);
        assertThatThrownBy(()->service.upload(student,image(),"scan.png","image/png",UUID.randomUUID(),auth,meta())).hasMessage("CONSENT_EVIDENCE_STORAGE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from consent_evidence_upload",Long.class)).isEqualTo(count);
    }
    @Test void MGT_CONSENT_unused_upload_cleanup_respects_student_hold_and_storage_failure()throws Exception{
        var asset=service.upload(student,image(),"scan.png","image/png",UUID.randomUUID(),auth,meta());
        jdbc.update("update media_asset set created_at=statement_timestamp()-interval '8 days',expires_at=statement_timestamp()-interval '1 second' where id=?",asset.id());
        UUID hold=UUID.randomUUID();jdbc.update("insert into retention_hold(id,target_type,target_id,reason,created_by) values(?,'STUDENT',?,'Legal dispute preservation',?)",hold,student,UUID.fromString(auth.getName()));
        assertThat(service.cleanup()).isZero();verify(storage,never()).delete(anyString());
        jdbc.update("update retention_hold set status='EXPIRED' where id=?",hold);
        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(anyString());
        assertThat(service.cleanup()).isZero();assertThat(consents.privateReadyEvidence(asset.id(),student)).isTrue();
        org.mockito.Mockito.reset(storage);assertThat(service.cleanup()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from media_asset where id=?",Integer.class,asset.id())).isZero();
    }
    UUID createStudent(UUID actor){UUID id=UUID.randomUUID();jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?,'Test','test',current_date,?,?)",id,actor,actor);return id;}
    static byte[] image()throws Exception{var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    static RequestMetadata meta(){return new RequestMetadata("req_private_evidence","127.0.0.1","test");}
    static EmbeddedPostgres start(){try{return EmbeddedPostgres.builder().start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
