package com.ramiart.admin.adminuser.infrastructure;

import static com.ramiart.admin.adminuser.application.AdminUserModels.*;
import com.ramiart.admin.adminuser.application.AdminUserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminUserRepository implements AdminUserRepository {
    private final JdbcClient jdbc;

    public JdbcAdminUserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public long count(Query query) {
        var filter = filter(query);
        return bind(jdbc.sql("select count(*) from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id" + filter.sql()), filter)
                .query(Long.class).single();
    }

    @Override public List<Summary> find(Query query, int limit, int offset) {
        String order = switch (query.sort()) {
            case "displayName,desc" -> "u.display_name desc, u.id asc";
            case "lastLoginAt,asc" -> "u.last_login_at asc nulls last, u.id asc";
            case "lastLoginAt,desc" -> "u.last_login_at desc nulls last, u.id asc";
            case "updatedAt,asc" -> "u.updated_at asc, u.id asc";
            case "updatedAt,desc" -> "u.updated_at desc, u.id asc";
            default -> "u.display_name asc, u.id asc";
        };
        var filter = filter(query);
        String sql = "select u.id,u.display_name,u.email,r.code role_code,r.name role_name,u.status,u.last_login_at,u.locked_until,u.password_must_change,u.created_at,u.updated_at,u.version from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id" + filter.sql() + " order by " + order + " limit :limit offset :offset";
        var statement = bind(jdbc.sql(sql), filter).param("limit", limit).param("offset", offset);
        return statement.query((rs, row) -> new Summary(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("email"), new Role(rs.getString("role_code"), rs.getString("role_name")),
                rs.getString("status"), timestamp(rs, "last_login_at"), timestamp(rs, "locked_until"),
                rs.getBoolean("password_must_change"), timestamp(rs, "created_at"), timestamp(rs, "updated_at"),
                rs.getLong("version"), false, List.of())).list();
    }

    @Override public java.util.Optional<Summary> findById(UUID id) {
        return jdbc.sql("select u.id,u.display_name,u.email,r.code role_code,r.name role_name,u.status,u.last_login_at,u.locked_until,u.password_must_change,u.created_at,u.updated_at,u.version from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id where u.id=:id")
                .param("id", id).query((rs, row) -> new Summary(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("email"), new Role(rs.getString("role_code"), rs.getString("role_name")),
                        rs.getString("status"), timestamp(rs, "last_login_at"), timestamp(rs, "locked_until"),
                        rs.getBoolean("password_must_change"), timestamp(rs, "created_at"), timestamp(rs, "updated_at"),
                        rs.getLong("version"), false, List.of())).optional();
    }

    private Filter filter(Query query) {
        List<String> conditions = new ArrayList<>();
        String keyword = query.keyword() == null ? null : query.keyword().trim().toLowerCase(java.util.Locale.ROOT);
        if (keyword != null) conditions.add("(lower(u.display_name) like :pattern or lower(u.email) like :pattern)");
        if (query.role() != null) conditions.add("r.code=:role");
        if (query.status() != null) conditions.add("u.status=:status");
        return new Filter(conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions), keyword, query.role(), query.status());
    }
    private org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bind(
            org.springframework.jdbc.core.simple.JdbcClient.StatementSpec statement, Filter filter) {
        if (filter.keyword() != null) statement = statement.param("pattern", "%" + filter.keyword() + "%");
        if (filter.role() != null) statement = statement.param("role", filter.role());
        if (filter.status() != null) statement = statement.param("status", filter.status());
        return statement;
    }

    private static OffsetDateTime timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.withOffsetSameInstant(java.time.ZoneOffset.ofHours(9));
    }
    private record Filter(String sql, String keyword, String role, String status) {}
}
