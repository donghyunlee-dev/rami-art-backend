package com.ramiart.admin.auth.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AuditRecorder {

    void record(Event event);

    record Event(
            Instant occurredAt,
            String requestId,
            String taskId,
            String eventClass,
            String actorType,
            UUID actorId,
            String actorDisplay,
            String action,
            String targetType,
            UUID targetId,
            String result,
            String reasonCode,
            String ipAddress,
            String userAgent,
            Map<String, Object> details) {
    }
}
