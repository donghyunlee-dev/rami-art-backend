package com.ramiart.admin.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
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
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Autowired DataSource dataSource;
    @Autowired MediaService service;
    @Autowired InMemoryStorage storage;
    JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({MediaService.class, JdbcMediaRepository.class, SecureImageValidator.class, JdbcAuditRecorder.class})
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
        assertThat(first.publicUrl()).startsWith("https://cdn.example.test/media/2026/09/");
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
    void storageFailuresLeaveDatabaseConsistentAndCleanupRetainsRetryableRows() throws Exception {
        UUID actor = actor();
        storage.failWrites = true;
        assertThatThrownBy(() -> service.upload(png(8, 8), "bad.png", "image/png", actor,
                UUID.randomUUID(), metadata())).isInstanceOf(MediaException.class)
                .extracting("code").isEqualTo("MEDIA_STORAGE_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from media_asset where created_by=?", Integer.class, actor)).isZero();

        storage.failWrites = false;
        var asset = service.upload(png(8, 8), "old.png", "image/png", actor, UUID.randomUUID(), metadata());
        jdbc.update("update media_asset set created_at=?, expires_at=? where id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(8 * 24 * 60 * 60L)),
                java.sql.Timestamp.from(NOW.minusSeconds(1)), asset.id());
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
