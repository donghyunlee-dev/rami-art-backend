package com.ramiart.admin.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

public final class PostgresScriptRunner {

    private PostgresScriptRunner() {
    }

    public static void execute(DataSource dataSource, Path script) throws IOException, SQLException {
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new SQLException("Failed to execute PostgreSQL script: " + script, exception);
        }
    }
}
