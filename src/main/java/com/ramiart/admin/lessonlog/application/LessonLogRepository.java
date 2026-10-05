package com.ramiart.admin.lessonlog.application;

import static com.ramiart.admin.lessonlog.application.LessonLogModels.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LessonLogRepository {
    record StoredLog(UUID id, UUID sessionId, int revision, String status, UUID basedOnLogId,
            UUID planItemId, String actualTitle, List<String> activities, List<String> materials,
            String changeReason, byte[] overallNoteCiphertext, String amendReason, long version,
            UUID createdBy, String createdByName, Instant createdAt, UUID finalizedBy,
            String finalizedByName, Instant finalizedAt) {}
    record StoredStudent(UUID id, UUID studentId, String attendanceStatus, String participation,
            byte[] progressCiphertext, byte[] observationCiphertext, byte[] absenceCiphertext) {}
    record StudentWriteData(UUID recordId, UUID studentId, String attendanceStatus, String participation,
            byte[] progressCiphertext, byte[] observationCiphertext, byte[] absenceCiphertext,
            List<UUID> artworkAssetIds) {}

    Optional<Session> findSession(UUID sessionId, boolean lock);
    List<String> assignedStaff(UUID classGroupId, java.time.LocalDate onDate);
    boolean isOwner(UUID actorId);
    boolean managesSession(UUID actorId, UUID classGroupId, java.time.LocalDate onDate);
    Optional<PlanItem> findPlanItem(UUID itemId);
    List<Target> attendanceTargets(UUID sessionId);
    List<StoredLog> revisions(UUID sessionId, boolean lock);
    Optional<StoredLog> findLog(UUID logId, boolean lock);
    List<StoredStudent> studentRecords(UUID logId);
    List<UUID> artworkAssets(UUID recordId);
    boolean artworkAssetsReady(List<UUID> assetIds);
    UUID insertDraft(Session session, PlanItem plan, UUID actorId, String amendReason, UUID basedOnLogId,
            StoredLog source, List<StoredStudent> sourceRecords);
    int updateDraft(UUID logId, long version, SaveWrite write, byte[] overallNoteCiphertext);
    void replaceStudentRecords(UUID logId, List<StudentWriteData> records);
    int finalizeDraft(UUID logId, long version, UUID actorId, Instant finalizedAt);
    int amendPrevious(UUID logId);
    boolean claimIdempotency(String scope, UUID key, String requestHash);
    Optional<String> idempotencyHash(String scope, UUID key);
    Optional<UUID> idempotencyResource(String scope, UUID key);
    void completeIdempotency(String scope, UUID key, UUID resourceId, int status);
    String adminDisplayName(UUID actorId);
    void insertArtworkReferences(List<UUID> assetIds, String ownerType, UUID ownerId);
    void removeArtworkReferences(UUID ownerId);

}
