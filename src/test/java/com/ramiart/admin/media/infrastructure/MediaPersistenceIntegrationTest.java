package com.ramiart.admin.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.consent.infrastructure.JdbcConsentRepository;
import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaModels.RequestMetadata;
import com.ramiart.admin.media.application.MediaService;
import com.ramiart.admin.media.application.MediaStorage;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
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

@SpringBootTest(classes = MediaPersistenceIntegrationTest.TestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789);

    @Autowired DataSource dataSource;
    @Autowired MediaService service;
    @Autowired JdbcMediaRepository repository;
    @Autowired JdbcConsentRepository consentRepository;
    @Autowired InMemoryStorage storage;
    JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({MediaService.class, JdbcMediaRepository.class, JdbcConsentRepository.class, SecureImageValidator.class, JdbcAuditRecorder.class})
    static class TestConfiguration {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean InMemoryStorage storage() { return new InMemoryStorage(); }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MediaPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("admin.media.public-base-url", () -> "https://cdn.example.test");
    }

    @BeforeEach
    void reset() throws Exception {
        storage.objects.clear();
        storage.failWrites = false;
        storage.failDeletes = false;
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
    void uploadIsSanitizedIdempotentAuditedAndDoesNotExposeInternalFields() throws Exception {
        UUID actor = actor();
        UUID key = UUID.randomUUID();
        byte[] png = png(40, 30);
        var first = service.upload(png, "작품.png", "image/png", actor, key, metadata());
        var replay = service.upload(png, "작품.png", "image/png", actor, key, metadata());

        assertThat(replay).isEqualTo(first);
        assertThat(first.publicUrl())
                .matches("https://cdn\\.example\\.test/media/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(first.toString()).doesNotContain("public-media").doesNotContain("sha256");
        assertThat(storage.objects).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from media_asset where id=?", Integer.class, first.id())).isOne();
        String audit = jdbc.queryForObject("select details::text from audit_log where action='MEDIA_ASSET_UPLOADED'", String.class);
        assertThat(audit).doesNotContain("storage").doesNotContain("sha256").doesNotContain(first.id().toString());

        assertThatThrownBy(() -> service.upload(png(41, 30), "작품.png", "image/png", actor, key, metadata()))
                .isInstanceOf(MediaException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(storage.objects).hasSize(1);
    }

    @Test
    void referencedAssetsCannotBeDeletedAndDeleteIsReplaySafe() throws Exception {
        UUID actor = actor();
        var asset = service.upload(png(10, 10), "logo.png", "image/png", actor, UUID.randomUUID(), metadata());
        UUID owner = UUID.randomUUID();
        jdbc.update("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name) values (?, 'SITE_BRAND_CONFIG', ?, 'logo')",
                asset.id(), owner);
        assertThatThrownBy(() -> service.delete(asset.id(), actor, UUID.randomUUID(), metadata()))
                .isInstanceOf(MediaException.class).extracting("code").isEqualTo("MEDIA_ASSET_IN_USE");
        assertThat(storage.objects).hasSize(1);

        jdbc.update("delete from media_asset_reference where asset_id=?", asset.id());
        UUID deleteKey = UUID.randomUUID();
        service.delete(asset.id(), actor, deleteKey, metadata());
        service.delete(asset.id(), actor, deleteKey, metadata());
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from media_asset where id=?", Integer.class, asset.id())).isZero();
    }

    @Test
    void referencesShowCurrentPublicContentAndKeepPrivateOwnersOutOfResults() throws Exception {
        UUID actor = actor();
        var asset = service.upload(png(12, 12), "artwork.png", "image/png", actor, UUID.randomUUID(), metadata());
        UUID revisionId = UUID.randomUUID();
        UUID artworkId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        jdbc.update("insert into course(id,code,name,created_by,updated_by) values(?,?,'작품 공개 테스트 과정',?,?)",
                courseId, "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), actor, actor);
        jdbc.update("""
                insert into gallery_artwork(id,artwork_id,revision,status,visible,title,course_id,audience_label,medium,
                    description,media_asset_id,alt_text,consent_exemption_reason,created_by,published_by,published_at)
                values (?, ?, 1, 'PUBLISHED', true, '수채화', ?, '초등', '수채화', '색을 관찰한 작품', ?, '푸른색과 노란색이 겹친 그림', '이전 동의 기록 확인 완료', ?, ?, statement_timestamp())
                """, revisionId, artworkId, courseId, asset.id(), actor, actor);
        jdbc.update("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state) values(?,'GALLERY_ARTWORK',?,'artworkImage','PUBLISHED')",
                asset.id(), revisionId);
        jdbc.update("insert into media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state) values(?,'STUDENT_LESSON_RECORD',?,'artwork','PRIVATE')",
                asset.id(), UUID.randomUUID());

        var references = repository.references(asset.id());

        assertThat(references).hasSize(1);
        assertThat(references.getFirst().ownerType()).isEqualTo("GALLERY_ARTWORK");
        assertThat(references.getFirst().targetId()).isEqualTo(artworkId);
        assertThat(references.getFirst().revisionId()).isEqualTo(revisionId);
        assertThat(references.getFirst().title()).isEqualTo("수채화");
        assertThat(references.getFirst().publicState()).isEqualTo("PUBLIC");
        assertThat(references.getFirst().currentlyPublic()).isTrue();
    }

    @Test
    void consentImpactLookupRetainsPublishedArtworkAfterRevocation() throws Exception {
        UUID actor = actor();
        var asset = service.upload(png(12, 12), "consented-artwork.png", "image/png", actor, UUID.randomUUID(), metadata());
        UUID studentId = UUID.randomUUID();
        UUID guardianId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID consentId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID artworkId = UUID.randomUUID();
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,created_by,updated_by) values(?, '김학생', '김학생', current_date, ?, ?)", studentId, actor, actor);
        jdbc.update("insert into guardian_contact(id,student_id,name,relationship,phone_ciphertext,phone_hash,phone_last4,primary_contact,display_order) values(?,?, '보호자', 'MOTHER', ?, ?, '1234', true, 0)", guardianId, studentId, new byte[]{1, 2, 3}, "a".repeat(64));
        jdbc.update("insert into consent_policy(id,type,revision,status,title,body,created_by,published_by,published_at) values(?, 'MEDIA_PUBLICATION', 1, 'PUBLISHED', '작품 공개', '작품 공개 동의 문안', ?, ?, statement_timestamp())", policyId, actor, actor);
        jdbc.update("insert into student_consent(id,student_id,consent_policy_id,policy_type,guardian_contact_id,method,status,consented_at,created_by) values(?,?,?,'MEDIA_PUBLICATION',?,'PAPER','ACTIVE',statement_timestamp(),?)", consentId, studentId, policyId, guardianId, actor);
        jdbc.update("insert into course(id,code,name,created_by,updated_by) values(?,?,'동의 영향 테스트 과정',?,?)", courseId, "CONSENT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), actor, actor);
        jdbc.update("""
                insert into gallery_artwork(id,artwork_id,revision,status,visible,title,course_id,audience_label,medium,
                    description,media_asset_id,alt_text,student_consent_id,created_by,published_by,published_at)
                values (?, ?, 1, 'PUBLISHED', true, '동의 작품', ?, '초등', '수채화', '작품 설명', ?, '파란색과 붉은색이 겹친 그림', ?, ?, ?, statement_timestamp())
                """, revisionId, artworkId, courseId, asset.id(), consentId, actor, actor);

        var before = consentRepository.publicArtworkReferences(consentId);
        jdbc.update("update student_consent set status='REVOKED',revoked_at=statement_timestamp(),revoked_by=?,revoke_reason='보호자 요청에 따른 철회' where id=?", actor, consentId);
        var after = consentRepository.publicArtworkReferences(consentId);

        assertThat(before).hasSize(1);
        assertThat(before.getFirst().currentlyPublic()).isTrue();
        assertThat(before.getFirst().artworkId()).isEqualTo(artworkId);
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().currentlyPublic()).isFalse();
    }

    @Test
    void storageFailuresLeaveDatabaseConsistentAndCleanupRetainsRetryableRows() throws Exception {
        UUID actor = actor();
        storage.failWrites = true;
        assertThatThrownBy(() -> service.upload(png(8, 8), "bad.png", "image/png", actor,
                UUID.randomUUID(), metadata())).isInstanceOf(MediaException.class)
                .extracting("code").isEqualTo("MEDIA_STORAGE_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from media_asset where created_by=?", Integer.class, actor)).isZero();

        storage.failWrites = false;
        var asset = service.upload(png(8, 8), "old.png", "image/png", actor, UUID.randomUUID(), metadata());
        Instant cleanupTime = NOW;
        jdbc.update("update media_asset set created_at=?, expires_at=? where id=?",
                java.sql.Timestamp.from(cleanupTime.minusSeconds(8 * 24 * 60 * 60L)),
                java.sql.Timestamp.from(cleanupTime.minusSeconds(1)), asset.id());
        storage.failDeletes = true;
        assertThat(service.cleanupExpired(10)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from media_asset where id=?", Integer.class, asset.id())).isOne();
        storage.failDeletes = false;
        assertThat(service.cleanupExpired(10)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from media_asset where id=?", Integer.class, asset.id())).isZero();
    }

    private UUID actor() { return jdbc.queryForObject("select id from admin_user limit 1", UUID.class); }
    private static RequestMetadata metadata() { return new RequestMetadata("req_media", "127.0.0.1", "integration-test"); }
    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
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
        return current.getParent();
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

    static final class InMemoryStorage implements MediaStorage {
        final Map<String, byte[]> objects = new HashMap<>();
        boolean failWrites;
        boolean failDeletes;
        @Override public void store(String key, byte[] content) {
            if (failWrites) throw new MediaException("MEDIA_STORAGE_FAILED");
            objects.put(key, content.clone());
        }
        @Override public void delete(String key) {
            if (failDeletes) throw new MediaException("MEDIA_STORAGE_FAILED");
            objects.remove(key);
        }
    }
}
