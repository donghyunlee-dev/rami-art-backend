package com.ramiart.admin.sitebrand.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.sitebrand.application.SiteBrandModels.BrandWrite;
import com.ramiart.admin.sitebrand.application.SiteBrandService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

@SpringBootTest(classes = SiteBrandPersistenceIntegrationTest.TestConfiguration.class)
class SiteBrandPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    @Autowired DataSource dataSource;
    @Autowired SiteBrandService service;
    JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SiteBrandService.class, JdbcSiteBrandRepository.class, JdbcAuditRecorder.class})
    static class TestConfiguration {
        @Bean java.time.Clock clock() { return java.time.Clock.systemUTC(); }
    }

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SiteBrandPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeEach void resetDatabase() throws SQLException, IOException {
        jdbc = new JdbcTemplate(dataSource);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            createRoleIfMissing(s, "anon"); createRoleIfMissing(s, "authenticated");
            s.execute("drop schema if exists public cascade"); s.execute("drop schema if exists extensions cascade");
            s.execute("create schema public"); s.execute("create schema extensions");
        } catch (SQLException e) { if (!"42710".equals(e.getSQLState())) throw e; }
        try (var paths = Files.list(projectRoot().resolve("supabase/migrations"))) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".sql")).sorted().toList()) {
                PostgresScriptRunner.execute(dataSource, p);
            }
        }
        PostgresScriptRunner.execute(dataSource, projectRoot().resolve("supabase/seed.sql"));
    }

    @Test void draftPreviewPublishAreVersionedIdempotentAndAtomic() {
        UUID actor = jdbc.queryForObject("select id from admin_user limit 1", UUID.class);
        var meta = new SiteBrandService.RequestMetadata("req_brand", "127.0.0.1", "integration");
        UUID createKey = UUID.randomUUID();
        var draft = service.createDraft(actor, createKey, meta);
        assertThat(service.createDraft(actor, createKey, meta).id()).isEqualTo(draft.id());
        BrandWrite write = sample(draft.version());
        assertThat(service.preview(write).publishable()).isTrue();
        var saved = service.updateDraft(draft.id(), write, actor, UUID.randomUUID(), meta);
        var published = service.publish(draft.id(), saved.version(), actor, UUID.randomUUID(), meta);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select count(*) from cache_invalidation_event", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action like 'SITE_BRAND_%'", Integer.class))
                .isEqualTo(3);
        assertThat(service.publicBrand().brandName()).isEqualTo("Studio");
    }

    private BrandWrite sample(Long version) {
        UUID logo = jdbc.queryForObject("select id from media_asset where storage_key='seed/generic-brand-logo.png'", UUID.class);
        UUID icon = jdbc.queryForObject("select id from media_asset where storage_key='seed/generic-brand-favicon.png'", UUID.class);
        return new BrandWrite(version, "Studio", "Studio", logo, "Studio logo", icon, null,
                "#1F2937", "#F59E0B", "SYSTEM_SANS", "https://studio.example", "Studio", "Art studio", null, null);
    }
    private static EmbeddedPostgres startPostgres() { try { return EmbeddedPostgres.builder().start(); } catch (IOException e) { throw new ExceptionInInitializerError(e); } }
    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var result = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + role + "')")) {
            result.next(); if (!result.getBoolean(1)) statement.execute("create role " + role);
        }
    }
    private static String jdbcUrl() {
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) { return connection.getMetaData().getURL(); }
        catch (SQLException e) { throw new IllegalStateException("embedded PostgreSQL URL lookup failed", e); }
    }
    private static Path projectRoot() { Path p = Path.of("").toAbsolutePath(); while (p != null && !Files.isDirectory(p.resolve("supabase/migrations"))) p = p.getParent(); if (p == null) throw new IllegalStateException("project root not found"); return p; }
    @AfterAll static void stopPostgres() throws IOException { POSTGRES.close(); }
}
