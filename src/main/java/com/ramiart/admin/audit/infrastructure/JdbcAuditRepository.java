package com.ramiart.admin.audit.infrastructure;

import com.ramiart.admin.audit.application.AuditRepository;
import com.ramiart.admin.audit.application.AuditRepository.AuditFilter;
import com.ramiart.admin.audit.application.AuditRepository.AuditRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditRepository {
    private static final String SELECT = """
            select a.id,a.occurred_at,a.request_id,a.task_id,a.event_class,a.actor_type,
                   case when actor.id is null then null else a.actor_id end actor_id,
                   coalesce(a.actor_display,actor.display_name) actor_display,a.action,a.target_type,a.target_id,
                   a.target_display,a.result,a.reason_code,a.ip_address::text ip_address,a.user_agent,a.details::text details
              from audit_log a left join admin_user actor on actor.id=a.actor_id
            """;
    private final JdbcClient jdbc;
    public JdbcAuditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public List<AuditRow> find(AuditFilter f, OffsetDateTime beforeAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where a.occurred_at>=:from and a.occurred_at<:to");
        if (f.actorType() != null) sql.append(" and a.actor_type=:actorType");
        if (f.actorId() != null) sql.append(" and a.actor_id=:actorId");
        if (f.taskId() != null) sql.append(" and a.task_id=:taskId");
        if (f.action() != null) sql.append(" and a.action=:action");
        if (f.result() != null) sql.append(" and a.result=:result");
        if (beforeAt != null) sql.append(" and (a.occurred_at,a.id)<(:beforeAt,:beforeId)");
        sql.append(" order by a.occurred_at desc,a.id desc limit :limit");
        var spec = jdbc.sql(sql.toString()).param("from", f.from()).param("to", f.to()).param("limit", limit);
        if (f.actorType() != null) spec = spec.param("actorType", f.actorType());
        if (f.actorId() != null) spec = spec.param("actorId", f.actorId());
        if (f.taskId() != null) spec = spec.param("taskId", f.taskId());
        if (f.action() != null) spec = spec.param("action", f.action());
        if (f.result() != null) spec = spec.param("result", f.result());
        if (beforeAt != null) spec = spec.param("beforeAt", beforeAt).param("beforeId", beforeId);
        return spec.query((rs, n) -> new AuditRow(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("request_id"), rs.getString("task_id"), rs.getString("event_class"), rs.getString("actor_type"),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_display"), rs.getString("action"), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class), rs.getString("target_display"), rs.getString("result"), rs.getString("reason_code"),
                rs.getString("ip_address"), rs.getString("user_agent"), rs.getString("details"))).list();
    }

    @Override public Optional<AuditRow> findById(UUID id) {
        return jdbc.sql(SELECT + " where a.id=:id").param("id", id).query((rs, n) -> new AuditRow(
                rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("request_id"),
                rs.getString("task_id"), rs.getString("event_class"), rs.getString("actor_type"), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_display"), rs.getString("action"), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                rs.getString("target_display"), rs.getString("result"), rs.getString("reason_code"), rs.getString("ip_address"),
                rs.getString("user_agent"), rs.getString("details"))).optional();
    }
}
