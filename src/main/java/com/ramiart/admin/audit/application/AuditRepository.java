package com.ramiart.admin.audit.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRepository {
    List<AuditRow> find(AuditFilter filter, OffsetDateTime beforeAt, UUID beforeId, int limit);
    Optional<AuditRow> findById(UUID id);
    record AuditFilter(OffsetDateTime from, OffsetDateTime to, String actorType, UUID actorId,
            String taskId, String action, String result) {}
    record AuditRow(UUID id, OffsetDateTime occurredAt, String requestId, String taskId, String eventClass,
            String actorType, UUID actorId, String actorDisplay, String action, String targetType,
            UUID targetId, String targetDisplay, String result, String reasonCode, String ipAddress,
            String userAgent, String detailsJson) {}
}
