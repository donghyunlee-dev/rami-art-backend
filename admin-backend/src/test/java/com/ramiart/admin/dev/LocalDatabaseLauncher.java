package com.ramiart.admin.dev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;

public final class LocalDatabaseLauncher {

    private static final int DEFAULT_PORT = 54322;

    private LocalDatabaseLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("LOCAL_ADMIN_DB_PORT",
                Integer.toString(DEFAULT_PORT)));
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path migrations = projectRoot.resolve("supabase/migrations");
        Path seed = projectRoot.resolve("supabase/seed.sql");
        requireFileLayout(migrations, seed);

        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> close(postgres), "local-postgres-shutdown"));
        createRequiredRoles(postgres);
        applyDatabaseFiles(postgres, migrations, seed);

        System.out.printf("Local admin database is ready: jdbc:postgresql://127.0.0.1:%d/postgres%n", port);
        System.out.println("Seed admin: owner@rami.local (temporary password from backend/README.md)");
        new CountDownLatch(1).await();
    }

    private static void requireFileLayout(Path migrations, Path seed) {
        if (!Files.isDirectory(migrations) || !Files.isRegularFile(seed)) {
            throw new IllegalStateException("Run localDatabase from the repository root");
        }
    }

    private static void createRequiredRoles(EmbeddedPostgres postgres) throws SQLException {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            createRoleIfMissing(statement, "anon");
            createRoleIfMissing(statement, "authenticated");
            statement.execute("create schema if not exists extensions");
        }
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var resultSet = statement.executeQuery(
                "select exists(select 1 from pg_roles where rolname = '" + role + "')")) {
            resultSet.next();
            if (!resultSet.getBoolean(1)) {
                statement.execute("create role " + role);
            }
        }
    }

    private static void applyDatabaseFiles(EmbeddedPostgres postgres, Path migrations, Path seed)
            throws IOException, SQLException {
        try (var paths = Files.list(migrations)) {
            for (Path migration : paths.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                PostgresScriptRunner.execute(postgres.getPostgresDatabase(), migration);
            }
        }
        PostgresScriptRunner.execute(postgres.getPostgresDatabase(), seed);
    }

    private static void close(EmbeddedPostgres postgres) {
        try {
            postgres.close();
        } catch (IOException exception) {
            System.err.println("Failed to stop local PostgreSQL: " + exception.getMessage());
        }
    }
}
