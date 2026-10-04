package com.ramiart.admin.auth.infrastructure;

import com.ramiart.admin.auth.application.AuditRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class JdbcAuditRecorder implements AuditRecorder {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcAuditRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(Event event) {
        jdbcClient.sql("""
                        insert into audit_log (
                            occurred_at, request_id, task_id, event_class, actor_type,
                            actor_id, actor_display, action, target_type, target_id, target_display,
                            result, reason_code, ip_address, user_agent, details
                        ) values (
                            :occurred_at, :request_id, :task_id, :event_class, :actor_type,
                            :actor_id, coalesce(:actor_display, (select display_name from admin_user where id=:actor_id)),
                            :action, :target_type, :target_id,
                            case :target_type
                              when 'STUDENT' then coalesce((select left(student_name,1) || repeat('•', greatest(char_length(student_name)-1,1)) from student where id=:target_id), '원생')
                              when 'INQUIRY' then '문의'
                              when 'ENROLLMENT_CASE' then '상담 등록'
                              when 'ATTENDANCE_SESSION' then coalesce((select class_name_snapshot from attendance_session where id=:target_id), '출석 세션')
                              when 'TUITION_BILLING' then '수업료 청구'
                              when 'ADMIN_USER' then '관리자 계정'
                              when 'MONTHLY_SCHEDULE' then coalesce((select year_month from monthly_schedule where id=:target_id), '월간 시간표')
                              else null
                            end,
                            :result, :reason_code, cast(:ip_address as inet), :user_agent,
                            cast(:details as jsonb)
                        )
                        """)
                .param("occurred_at", JdbcTimestamps.offsetDateTime(event.occurredAt()))
                .param("request_id", event.requestId())
                .param("task_id", event.taskId())
                .param("event_class", event.eventClass())
                .param("actor_type", event.actorType())
                .param("actor_id", event.actorId())
                .param("actor_display", event.actorDisplay())
                .param("action", event.action())
                .param("target_type", event.targetType())
                .param("target_id", event.targetId())
                .param("result", event.result())
                .param("reason_code", event.reasonCode())
                .param("ip_address", event.ipAddress())
                .param("user_agent", event.userAgent())
                .param("details", toJson(event))
                .update();
    }

    private String toJson(Event event) {
        try {
            return objectMapper.writeValueAsString(event.details());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("audit details are not serializable", exception);
        }
    }
}
