package com.rami.artstudio.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared embedded PostgreSQL server with a separate database for each legacy integration suite. */
public final class LegacyEmbeddedPostgres {

    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LegacyEmbeddedPostgres::close, "legacy-test-postgres-shutdown"));
    }

    private LegacyEmbeddedPostgres() {
    }

    public static void configure(DynamicPropertyRegistry registry, String databaseName) {
        ensureDatabase(databaseName);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl(databaseName));
        dataSource.setUsername("postgres");
        dataSource.setPassword("postgres");
        LegacyFlywayTestBootstrap.prepare(dataSource);
        registry.add("spring.datasource.url", () -> jdbcUrl(databaseName));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static synchronized void ensureDatabase(String databaseName) {
        if (!DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalArgumentException("Invalid embedded test database name");
        }
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "select exists(select 1 from pg_database where datname = ?)");) {
            query.setString(1, databaseName);
            try (var result = query.executeQuery()) {
                result.next();
                if (result.getBoolean(1)) return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("create database " + databaseName);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not prepare isolated embedded test database", exception);
        }
    }

    private static String jdbcUrl(String databaseName) {
        try (Connection connection = POSTGRES.getPostgresDatabase().getConnection()) {
            String baseUrl = connection.getMetaData().getURL();
            return baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + databaseName;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not resolve embedded test database URL", exception);
        }
    }

    private static void close() {
        try {
            POSTGRES.close();
        } catch (IOException exception) {
            System.err.println("Could not stop isolated legacy test PostgreSQL: " + exception.getMessage());
        }
    }
}
