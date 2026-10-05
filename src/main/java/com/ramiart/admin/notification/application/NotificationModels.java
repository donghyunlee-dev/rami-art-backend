package com.ramiart.admin.notification.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationModels {
    private NotificationModels() {}
    public record RecipientFilter(@JsonProperty("studentIds") List<UUID> studentIds,
            @JsonProperty("classGroupIds") List<UUID> classGroupIds) {}
    public record DraftRequest(@JsonProperty("type") String type, @JsonProperty("channel") String channel,
            @JsonProperty("recipientFilter") RecipientFilter recipientFilter,
            @JsonProperty("subjectTemplate") String subjectTemplate, @JsonProperty("bodyTemplate") String bodyTemplate,
            @JsonProperty("variables") Map<String,String> variables, @JsonProperty("scheduledAt") OffsetDateTime scheduledAt,
            @JsonProperty("optionalNotice") boolean optionalNotice) {}
    public record QueueRequest(@JsonProperty("type") String type, @JsonProperty("channel") String channel,
            @JsonProperty("recipientFilter") RecipientFilter recipientFilter,
            @JsonProperty("subjectTemplate") String subjectTemplate, @JsonProperty("bodyTemplate") String bodyTemplate,
            @JsonProperty("variables") Map<String,String> variables, @JsonProperty("scheduledAt") OffsetDateTime scheduledAt,
            @JsonProperty("optionalNotice") boolean optionalNotice, @JsonProperty("previewToken") String previewToken) {}
    public record RenderedSample(String studentName, String guardianName, String subject, String body) {}
    public record Preview(int eligibleCount, int missingContactCount, int consentExcludedCount, int deduplicatedCount,
            List<RenderedSample> renderedSamples, List<String> warnings, String previewToken, OffsetDateTime expiresAt) {}
    public record QueueResult(UUID batchKey, int queuedCount, int statusCount) {}
    public record MessageSummary(UUID id, UUID batchKey, String type, String channel, String status,
            OffsetDateTime scheduledAt, OffsetDateTime createdAt, long version) {}
    public record Attempt(int attemptNumber, String result, String provider, String errorCode,
            OffsetDateTime startedAt, OffsetDateTime completedAt) {}
    public record MessageDetail(MessageSummary message, String subject, String body, String recipientLast4,
            int attemptCount, String lastErrorCode, List<Attempt> attempts) {}
    public record StoredMessageDetail(MessageSummary message, byte[] subject, byte[] body, String recipientLast4,
            int attemptCount, String lastErrorCode, List<Attempt> attempts) {}
    public record MessagePage(List<MessageSummary> items, int page, int size, long total) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
}
