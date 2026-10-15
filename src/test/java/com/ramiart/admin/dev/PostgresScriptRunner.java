package com.ramiart.admin.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;

public final class PostgresScriptRunner {

    private PostgresScriptRunner() {
    }

    public static void execute(DataSource dataSource, Path script) throws IOException, SQLException {
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        boolean schemaConsolidation = script.getFileName().toString().equals("202610150001_admin_schema_consolidation.sql");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
            if (schemaConsolidation) {
                try (var user = statement.executeQuery("select current_user")) {
                    user.next();
                    String role = user.getString(1).replace("\"", "\"\"");
                    statement.execute("alter role \"" + role + "\" set search_path to rami_art_studio, extensions");
                }
            }
            statement.execute("set search_path to rami_art_studio, extensions");
        } catch (SQLException exception) {
            throw new SQLException("Failed to execute PostgreSQL script: " + script, exception);
        }
        if (schemaConsolidation && dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.getHikariPoolMXBean().softEvictConnections();
        }
    }
}
