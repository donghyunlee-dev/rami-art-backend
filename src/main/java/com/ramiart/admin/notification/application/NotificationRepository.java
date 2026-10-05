package com.ramiart.admin.notification.application;

import static com.ramiart.admin.notification.application.NotificationModels.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    final class NotificationConflictException extends RuntimeException {
        private final String code;
        public NotificationConflictException(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    record Candidate(UUID studentId, String studentName, UUID guardianId, String guardianName,
            byte[] phoneCiphertext, String phoneHash, String phoneLast4, byte[] emailCiphertext,
            String emailHash, String emailDomain,
            UUID consentId, boolean hasChannelContact) {}
    record Dispatch(UUID id, int attemptNumber, String channel, byte[] recipient, byte[] subject, byte[] body,
            UUID consentId, boolean optionalNotice, OffsetDateTime startedAt) {}
    record Claim(boolean claimed, UUID resourceId) {}
    List<Candidate> candidates(RecipientFilter filter, OffsetDateTime at);
    Claim claim(String scope, UUID key, String requestHash);
    void complete(String scope, UUID key, UUID resourceId, int status);
    int batchQueuedCount(UUID batchId);
    void createBatch(UUID batchId, DraftRequest request, int eligible, int missing, int excluded,
            int deduplicated, String fingerprint, UUID actor, byte[] filter, byte[] subjectTemplate,
            byte[] bodyTemplate, byte[] variables);
    void createMessage(UUID id, UUID batchId, Candidate target, DraftRequest request, UUID actor,
            byte[] recipient, String recipientHash, String recipientLast4,
            byte[] subject, byte[] body, byte[] variables, String idempotencyScope);
    List<MessageSummary> page(int page, int size, String status, String type, String channel);
    long count(String status, String type, String channel);
    Optional<StoredMessageDetail> detail(UUID id);
    boolean cancel(UUID id, long version, String reason, UUID actor);
    boolean retry(UUID id);
    Optional<Dispatch> claimNext(String channel,OffsetDateTime now);
    void finish(Dispatch dispatch,String provider,String providerMessageId,String errorCode,boolean retryable,OffsetDateTime completedAt,OffsetDateTime nextAttemptAt);
}
