package com.ramiart.admin.homecontent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.homecontent.application.HomePageContentService;
import com.ramiart.admin.media.application.MediaUrlResolver;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomePageContentPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private DataSource dataSource = POSTGRES.getPostgresDatabase();
    private JdbcTemplate jdbc;
    private HomePageContentService service;
    private UUID actorId;
    private TransactionTemplate transaction;

    @BeforeAll
    void resetDatabase() throws SQLException, IOException {
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            createRoleIfMissing(statement, "anon");
            createRoleIfMissing(statement, "authenticated");
            statement.execute("drop schema if exists rami_art_studio cascade");
            statement.execute("drop schema if exists public cascade");
            statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");
            statement.execute("create schema extensions");
        }
        Path root = projectRoot();
        try (var migrations = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : migrations.filter(path -> path.getFileName().toString().matches("[0-9]{12,}_.+\\.sql")).sorted().toList()) {
                PostgresScriptRunner.execute(dataSource, migration);
            }
        }
        PostgresScriptRunner.execute(dataSource, root.resolve("supabase/seed.sql"));
        actorId = jdbc.queryForObject("select id from admin_user order by email limit 1", UUID.class);
        JdbcClient client = JdbcClient.create(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        service = new HomePageContentService(new JdbcHomePageContentRepository(client, mapper),
                new JdbcAuditRecorder(client, mapper), java.time.Clock.systemUTC(),
                new MediaUrlResolver("https://rami-art-backend.onrender.com"));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test
    void createDraftCompletesIdempotencyRecordAndCanReplayTheDraft() {
        UUID key = UUID.randomUUID();
        var metadata = new HomePageContentService.RequestMetadata("req_home_draft", "127.0.0.1", "integration-test");

        var draft = transaction.execute(status -> service.createDraft(null, actorId, key, metadata));
        var replay = transaction.execute(status -> service.createDraft(null, actorId, key, metadata));

        assertThat(draft).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(replay.id()).isEqualTo(draft.id());
        assertThat(jdbc.queryForObject("select state from idempotency_record where scope like '%:HOME_CONTENT_DRAFT_CREATE' and idempotency_key=?", String.class, key))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from home_page_content where status='DRAFT'", Integer.class)).isOne();
    }

    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var result = statement.executeQuery("select exists(select 1 from pg_roles where rolname='" + role + "')")) {
            result.next();
            if (!result.getBoolean(1)) statement.execute("create role " + role);
        }
    }

    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("supabase/migrations"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("project root not found");
        return path;
    }
}
