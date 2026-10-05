package com.ramiart.admin.audit.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AuditModels {
    private AuditModels() {}
    public record Option(String value, String label) {}
    public record TaskOption(String taskId, String label) {}
    public record ActionOption(String action, String label, List<String> taskIds, String eventClass,
            String targetType, String targetRouteTemplate, String requiredTargetPermission) {}
    public record Options(List<Option> actorTypes, List<Option> results, List<TaskOption> tasks,
            List<ActionOption> actions) {}
    public record Actor(String type, UUID id, String displayName) {}
    public record Target(String type, String display) {}
    public record TargetDetail(String type, UUID id, String display) {}
    public record Item(UUID id, OffsetDateTime occurredAt, Actor actor, String taskId, String action,
            String actionLabel, Target target, String result) {}
    public record PageInfo(int size, String nextCursor) {}
    public record ListResponse(List<Item> items, PageInfo page) {}
    public record Detail(UUID id, OffsetDateTime occurredAt, String requestId, String taskId, Actor actor,
            String action, String actionLabel, TargetDetail target, String result, String reasonCode,
            String ipAddress, String userAgent, List<AllowedDetail> details, String targetLink) {}
    public record AllowedDetail(String key, String label, Object before, Object after) {}
    public record Query(String from, String to, String actorType, UUID actorId, String taskId,
            String action, String result, String cursor, int size) {}
}
