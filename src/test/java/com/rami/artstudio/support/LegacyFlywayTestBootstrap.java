package com.rami.artstudio.support;

import com.ramiart.admin.dev.PostgresScriptRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** Prepares the shared public objects required by the legacy Flyway migrations in test databases. */
public final class LegacyFlywayTestBootstrap {

    private static final String[] PREREQUISITE_MIGRATIONS = {
        "202607120001_extensions_and_common.sql",
        "202607120003_audit_idempotency.sql"
    };

    private LegacyFlywayTestBootstrap() {
    }

    public static void prepare(DataSource dataSource) {
        createRequiredRoles(dataSource);
        Path migrations = projectRoot().resolve("supabase/migrations");
        try {
            for (String name : PREREQUISITE_MIGRATIONS) {
                Path migration = migrations.resolve(name);
                if (!Files.isRegularFile(migration)) {
                    throw new IllegalStateException("Required test migration is missing: " + name);
                }
                PostgresScriptRunner.execute(dataSource, migration);
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not prepare legacy test database prerequisites", exception);
        }
    }

    private static void createRequiredRoles(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            createRoleIfMissing(statement, "anon");
            createRoleIfMissing(statement, "authenticated");
            statement.execute("create schema if not exists extensions");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not prepare required test database roles", exception);
        }
    }

    private static void createRoleIfMissing(Statement statement, String role) throws SQLException {
        try (var result = statement.executeQuery(
                "select exists(select 1 from pg_roles where rolname = '" + role + "')")) {
            result.next();
            if (!result.getBoolean(1)) statement.execute("create role " + role);
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("supabase/migrations"))) return candidate;
        }
        throw new IllegalStateException("supabase/migrations directory was not found");
    }
}
