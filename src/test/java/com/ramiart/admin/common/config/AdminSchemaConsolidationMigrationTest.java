package com.ramiart.admin.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ramiart.admin.dev.PostgresScriptRunner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminSchemaConsolidationMigrationTest {

    private static final EmbeddedPostgres postgres = startPostgres();
    private static DataSource dataSource;

    @BeforeAll
    static void applyMigrations() throws Exception {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create role anon");
            statement.execute("create role authenticated");
            statement.execute("create role rami_backend");
            statement.execute("create schema extensions");
        }
        Path root = projectRoot();
        try (var files = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : files.filter(file -> file.getFileName().toString().matches("[0-9]{12,}_.+\\.sql")).sorted().toList()) {
                PostgresScriptRunner.execute(dataSource, migration);
            }
        }
    }

    @AfterAll
    static void closePostgres() throws IOException {
        postgres.close();
    }

    @Test
    void currentAdministratorObjectsEndInRamiSchemaWithReferenceDataAndPolicies() throws Exception {
        PostgresScriptRunner.execute(dataSource, verifyAdminSchemaScript());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("select count(*) from rami_art_studio.admin_role", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from rami_art_studio.admin_permission", Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from rami_art_studio.finance_category", Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from rami_art_studio.admin_role_permission", Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select has_table_privilege('rami_backend', 'rami_art_studio.admin_auth_rate_limit', 'select,insert,update,delete')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select exists(select 1 from pg_policies where schemaname='rami_art_studio' and tablename='admin_auth_rate_limit' and policyname='admin_auth_rate_limit_backend_all')", Boolean.class)).isTrue();
    }

    @Test
    void legacyCleanupRestoresTablesRequiredByBackendEntities() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("alter table rami_art_studio.admin_auth_rate_limit set schema public");
        }
        PostgresScriptRunner.execute(dataSource, backendRoot().resolve("src/main/resources/db/migration/V8__remove_unused_legacy_schema_objects.sql"));
        PostgresScriptRunner.execute(dataSource, backendRoot().resolve("src/main/resources/db/migration/V9__restore_backend_entity_tables.sql"));
        PostgresScriptRunner.execute(dataSource, verifyAdminSchemaScript());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.admin_refresh_tokens') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.admin_users') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.classes') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.class_tags') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.gallery_categories') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.gallery_works') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.admin_user') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('public.admin_auth_rate_limit') is null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('rami_art_studio.admin_auth_rate_limit') is not null", Boolean.class)).isTrue();
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("supabase/migrations"))) return candidate;
        }
        throw new IllegalStateException("supabase/migrations directory was not found");
    }

    private static Path backendRoot() {
        Path root = projectRoot();
        Path monorepoBackend = root.resolve("backend");
        return Files.isDirectory(monorepoBackend.resolve("src/main/resources/db/migration"))
                ? monorepoBackend
                : root;
    }

    private static Path verifyAdminSchemaScript() {
        Path script = projectRoot().resolve("scripts/verify-admin-schema.sql");
        return Files.isRegularFile(script)
                ? script
                : backendRoot().resolve("src/test/resources/scripts/verify-admin-schema.sql");
    }
}
