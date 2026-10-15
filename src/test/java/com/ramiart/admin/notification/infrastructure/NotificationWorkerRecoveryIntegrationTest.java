package com.ramiart.admin.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.notification.application.NotificationException;
import com.ramiart.admin.notification.application.NotificationModels.DraftRequest;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.notification.application.NotificationProvider;
import com.ramiart.admin.notification.application.NotificationRepository;
import com.ramiart.admin.notification.application.NotificationService;
import com.ramiart.admin.notification.application.NotificationTemplateRenderer;
import com.ramiart.admin.student.application.StudentDataProtector;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationWorkerRecoveryIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-12T00:00:00Z");
    private static final EmbeddedPostgres POSTGRES=startPostgres();
    private final org.springframework.jdbc.core.simple.JdbcClient client=org.springframework.jdbc.core.simple.JdbcClient.create(POSTGRES.getPostgresDatabase());
    private JdbcTemplate jdbc;
    private UUID actorId;

    @BeforeAll void applyCanonicalSchema() throws Exception {
        jdbc=new JdbcTemplate(POSTGRES.getPostgresDatabase());
        try(Connection connection=POSTGRES.getPostgresDatabase().getConnection();Statement statement=connection.createStatement()){
            createRoleIfMissing(statement,"anon");createRoleIfMissing(statement,"authenticated");
            statement.execute("drop schema if exists rami_art_studio cascade");
            statement.execute("drop schema if exists public cascade");statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");statement.execute("create schema extensions");
        }
        Path root=projectRoot();
        try(var files=Files.list(root.resolve("supabase/migrations"))){
            for(Path migration:files.filter(path->path.getFileName().toString().matches("[0-9]+_.*\\.sql")).sorted().toList())
                PostgresScriptRunner.execute(POSTGRES.getPostgresDatabase(),migration);
        }
        PostgresScriptRunner.execute(POSTGRES.getPostgresDatabase(),root.resolve("supabase/seed.sql"));
        actorId=jdbc.queryForObject("select id from admin_user order by email limit 1",UUID.class);
    }

    @AfterAll void closePostgres() throws IOException {POSTGRES.close();}

    @Test void recoversInterruptedEmailAndResendsWithProviderIdempotency() {
        UUID message=queueEmail();
        var repository=new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase()));
        var first=repository.claimNext("EMAIL",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).orElseThrow();
        var provider=new IdempotentFakeProvider();

        provider.send(first.id(),"guardian@example.test","Subject","Body");

        var worker=new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),
                Clock.fixed(NOW.plusSeconds(180),ZoneOffset.UTC));
        worker.poll();

        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select attempt_count from notification_message where id=?",Integer.class,message)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification_attempt where notification_message_id=?",Integer.class,message)).isEqualTo(2);
        assertThat(provider.calls).isEqualTo(2);
        assertThat(provider.deliveries).hasSize(1);
    }

    @Test void recoversClaimInterruptedBeforeProviderSend() {
        UUID message=queueEmail();
        var repository=new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase()));
        repository.claimNext("EMAIL",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).orElseThrow();
        var provider=new IdempotentFakeProvider();

        new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),Clock.fixed(NOW.plusSeconds(180),ZoneOffset.UTC)).poll();

        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("SENT");
        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.deliveries).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from notification_attempt where notification_message_id=? and error_code='WORKER_INTERRUPTED'",Integer.class,message)).isOne();
    }

    @Test void retriesTransientProviderFailureOnlyAfterBackoff() {
        UUID message=queueEmail();
        var repository=new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase()));
        var provider=new IdempotentFakeProvider(1);
        new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),Clock.fixed(NOW,ZoneOffset.UTC)).poll();
        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select next_attempt_at from notification_message where id=?",OffsetDateTime.class,message))
                .isEqualTo(OffsetDateTime.ofInstant(NOW.plusSeconds(60),ZoneOffset.UTC));

        new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),Clock.fixed(NOW.plusSeconds(59),ZoneOffset.UTC)).poll();
        assertThat(provider.calls).isEqualTo(1);
        new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC)).poll();

        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select count(*) from notification_attempt where notification_message_id=?",Integer.class,message)).isEqualTo(2);
    }

    @Test void cancelledQueueIsNotSentByWorker() {
        UUID message=queueEmail();
        var repository=new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase()));
        assertThat(repository.cancel(message,0,"합성 취소 사유",actorId)).isTrue();
        var provider=new IdempotentFakeProvider();
        new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),Clock.fixed(NOW,ZoneOffset.UTC)).poll();
        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("CANCELLED");
        assertThat(provider.calls).isZero();
    }

    @Test void revokedOptionalConsentCancelsQueueBeforeProviderSend() {
        UUID student=UUID.randomUUID(),guardian=UUID.randomUUID(),policy=UUID.randomUUID(),consent=UUID.randomUUID();
        UUID batch=UUID.randomUUID(),message=UUID.randomUUID();
        String scope=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?,'합성 원생','합성원생','2020-01-01',?,?)",student,actorId,actorId);
        jdbc.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4,email_ciphertext,email_hash,email_domain,preferred_channel,primary_contact) values(?,?,'합성 보호자','MOTHER',decode('01','hex'),repeat('c',64),'1234',decode('02','hex'),repeat('d',64),'example.test','EMAIL',true)",guardian,student);
        jdbc.update("insert into consent_policy(id,type,revision,status,title,body,created_by,published_by,published_at) values(?,'OPTIONAL_NOTIFICATION',1,'PUBLISHED','합성 선택 안내','합성 동의 정책',?,?,?)",policy,actorId,actorId,java.sql.Timestamp.from(NOW));
        jdbc.update("insert into student_consent(id,student_id,consent_policy_id,policy_type,guardian_contact_id,method,status,consented_at,created_by) values(?,?,?,'OPTIONAL_NOTIFICATION',?,'PAPER','ACTIVE',?,?)",consent,student,policy,guardian,java.sql.Timestamp.from(NOW),actorId);
        jdbc.update("insert into notification_batch(id,type,channel,scheduled_at,created_by) values(?,'GENERAL','EMAIL',?,?)",batch,java.sql.Timestamp.from(NOW),actorId);
        jdbc.update("insert into notification_message(id,batch_key,type,channel,student_id,guardian_contact_id,recipient_ciphertext,recipient_hash,recipient_last4,body_ciphertext,consent_id,status,scheduled_at,optional_notice,idempotency_scope,created_by) values(?,?,'GENERAL','EMAIL',?,?,decode('01','hex'),repeat('e',64),'1234',decode('02','hex'),?,'QUEUED',?,true,?,?)",message,batch,student,guardian,consent,java.sql.Timestamp.from(NOW),scope,actorId);
        jdbc.update("update student_consent set status='REVOKED',revoked_at=?,revoke_reason='합성 동의 철회',revoked_by=? where id=?",java.sql.Timestamp.from(NOW),actorId,consent);
        var provider=new IdempotentFakeProvider();

        new NotificationOutboxWorker(new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase())),new TextProtector(),List.of(provider),Clock.fixed(NOW.plusSeconds(1),ZoneOffset.UTC)).poll();

        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("CANCELLED");
        assertThat(provider.calls).isZero();
    }

    @Test void staleAttemptOutsideProviderIdempotencyWindowIsTerminal() {
        UUID message=queueEmail();
        var repository=new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase()));
        repository.claimNext("EMAIL",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).orElseThrow();
        var provider=new IdempotentFakeProvider();

        new NotificationOutboxWorker(repository,new TextProtector(),List.of(provider),Clock.fixed(NOW.plus(Duration.ofHours(25)),ZoneOffset.UTC)).poll();

        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select last_error_code from notification_message where id=?",String.class,message)).isEqualTo("PERMANENT_WORKER_INTERRUPTED");
        assertThat(provider.calls).isZero();
    }

    @Test void emailManualRetryAfterProviderIdempotencyWindowIsRejected() {
        UUID message=queueEmail();
        jdbc.update("update notification_message set status='FAILED',attempt_count=1,last_attempt_at=?,last_error_code='PROVIDER_UNAVAILABLE' where id=?",
                java.sql.Timestamp.from(NOW.minus(Duration.ofHours(25))),message);

        assertThat(new JdbcNotificationRepository(new NamedParameterJdbcTemplate(POSTGRES.getPostgresDatabase())).retry(message)).isFalse();
        assertThat(jdbc.queryForObject("select status from notification_message where id=?",String.class,message)).isEqualTo("FAILED");
    }

    @Test void rejectsUnconfiguredChannelBeforeReadingRecipients() {
        NotificationRepository repository=mock(NotificationRepository.class);
        var service=new NotificationService(repository,new NotificationTemplateRenderer(),new TextProtector(),mock(AuditRecorder.class),
                new ObjectMapper(),Clock.fixed(NOW,ZoneOffset.UTC),List.of());
        var auth=new TestingAuthenticationToken(UUID.randomUUID().toString(),null,List.of(new SimpleGrantedAuthority("NOTIFICATION_SEND")));
        var request=new DraftRequest("GENERAL","SMS",new com.ramiart.admin.notification.application.NotificationModels.RecipientFilter(List.of(),List.of()),
                null,"안내",Map.of(),OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),false);

        assertThatThrownBy(()->service.preview(request,auth)).isInstanceOf(NotificationException.class)
                .extracting("code").isEqualTo("NOTIFICATION_CHANNEL_NOT_CONFIGURED");
        verifyNoInteractions(repository);
    }

    private UUID queueEmail(){
        UUID batch=UUID.randomUUID(),message=UUID.randomUUID();TimestampValue times=new TimestampValue(NOW);
        String scope=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into notification_batch(id,type,channel,scheduled_at,created_by) values(?,'GENERAL','EMAIL',?,?)",batch,times.value(),actorId);
        jdbc.update("insert into notification_message(id,batch_key,type,channel,recipient_ciphertext,recipient_hash,recipient_last4,subject_ciphertext,body_ciphertext,status,scheduled_at,idempotency_scope,created_by) values(?,?,'GENERAL','EMAIL',decode('01','hex'),repeat('a',64),'1234',decode('02','hex'),decode('03','hex'),'QUEUED',?,?,?)",message,batch,times.value(),scope,actorId);
        return message;
    }

    private static final class IdempotentFakeProvider implements NotificationProvider {
        private final Map<UUID,String> deliveries=new HashMap<>();
        private int transientFailures;
        private int calls;
        IdempotentFakeProvider(){this(0);}
        IdempotentFakeProvider(int transientFailures){this.transientFailures=transientFailures;}
        @Override public String channel(){return "EMAIL";}
        @Override public Delivery send(UUID messageId,String recipient,String subject,String body){
            calls++;if(transientFailures>0){transientFailures--;throw new DeliveryException("FAKE_TEMPORARY",true);}
            String id=deliveries.computeIfAbsent(messageId,ignored->"fake-"+messageId);return new Delivery("FAKE",id);
        }
    }
    private static final class TextProtector implements StudentDataProtector {
        @Override public ProtectedValue protect(String value){return new ProtectedValue(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),"a".repeat(64));}
        @Override public String reveal(byte[] ciphertext){return new String(ciphertext,java.nio.charset.StandardCharsets.UTF_8);}
        @Override public String hash(String value){return "a".repeat(64);}
    }
    private record TimestampValue(java.sql.Timestamp value){TimestampValue(Instant instant){this(java.sql.Timestamp.from(instant));}}
    private static Path projectRoot(){Path current=Path.of("").toAbsolutePath().normalize();while(current!=null&&!Files.isDirectory(current.resolve("supabase/migrations")))current=current.getParent();if(current==null)throw new IllegalStateException("supabase migrations directory not found");return current;}
    private static EmbeddedPostgres startPostgres(){try{return EmbeddedPostgres.builder().start();}catch(IOException exception){throw new ExceptionInInitializerError(exception);}}
    private static void createRoleIfMissing(Statement statement,String role)throws SQLException{try(var rows=statement.executeQuery("select exists(select 1 from pg_roles where rolname='"+role+"')")){rows.next();if(!rows.getBoolean(1))statement.execute("create role "+role);}}
}
