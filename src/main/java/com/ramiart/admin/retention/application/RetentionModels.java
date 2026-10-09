package com.ramiart.admin.retention.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RetentionModels {
    private RetentionModels() {}
    public record Metadata(String requestId,String ipAddress,String userAgent) {}
    public record HoldWrite(String targetType,UUID targetId,String reason,Instant endsAt) {}
    public record PreviewWrite(String domain,Instant cutoffAt) {}
    public record RunWrite(UUID previewVersion,String reauthToken,String confirmation) {}
    public record Hold(UUID id,String targetType,UUID targetId,String reason,String status,Instant startsAt,
            Instant endsAt,Instant releasedAt,String releaseReason,UUID createdBy,UUID releasedBy,Instant createdAt) {}
    public record HoldPage(List<Hold> items,String nextCursor,boolean hasNext) {}
    public record Policy(String domain,String policyVersion,String retentionRule,Run recentRun) {}
    public record Policies(List<Policy> items) {}
    public record Preview(UUID previewVersion,String domain,String policyVersion,Instant cutoffAt,int candidateCount,
            int holdExcludedCount,int referenceExcludedCount,Instant expiresAt) {}
    public record Run(UUID id,UUID previewVersion,String domain,String status,int candidateCount,int holdExcludedCount,
            int referenceExcludedCount,int processedCount,int failedCount,Map<String,Integer> errorCodeCounts,
            Instant createdAt,Instant approvedAt,Instant startedAt,Instant completedAt) {}
    public record StoredRun(Run value,String policyVersion,Instant cutoffAt,String fingerprint,UUID actor) {}
    public record Candidate(UUID id,boolean held,boolean referenced,String version,String storageKey) {}
    public static final class RetentionException extends RuntimeException {
        public RetentionException(String code){super(code);}
    }
}
