package com.ramiart.admin.inquiry.application;

import com.ramiart.admin.inquiry.application.InquiryModels.InquiryQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InquiryRepository {
    record Claim(boolean claimed, UUID resourceId) {}
    record CourseRecord(UUID id, String name, boolean active) {}
    record InquiryRecord(UUID id, byte[] nameCiphertext, byte[] phoneCiphertext, String phoneLast4,
            byte[] messageCiphertext, CourseRecord course, String status, String consentPolicyVersion,
            Instant consentedAt, Instant receivedAt, Instant readAt, UUID readById, String readByName,
            String notificationStatus, Instant notificationAttemptedAt, long version, Instant lastActivityAt) {}
    record ActivityRecord(UUID id, String fromStatus, String toStatus, byte[] noteCiphertext,
            UUID createdById, String createdByName, Instant createdAt) {}
    record PageRecords(List<InquiryRecord> items, long total, long unread, long stale) {}

    Optional<CourseRecord> findPublicCourseForSubmission(UUID id);
    List<CourseRecord> findPublicCourseOptions();
    boolean coursesExist(List<UUID> ids);
    List<CourseRecord> findCourseOptions();
    int incrementRateLimit(String type, String bucketHash, Instant windowStart);
    Claim claim(String scope, UUID key, String requestHash);
    void complete(String scope, UUID key, UUID resourceId, int status);
    void insert(UUID id, byte[] name, String nameHash, byte[] phone, String phoneHash, String phoneLast4,
            UUID courseId, String courseNameSnapshot, byte[] message, String policyVersion, Instant now);
    PageRecords findPage(InquiryQuery query, String keywordHash, Instant staleBefore);
    Optional<InquiryRecord> find(UUID id);
    List<ActivityRecord> findActivities(UUID id);
    int markRead(UUID id, long version, UUID actorId, Instant now);
    int transition(UUID id, long version, String fromStatus, String toStatus);
    UUID insertActivity(UUID inquiryId, String fromStatus, String toStatus, byte[] note,
            UUID actorId, long inquiryVersion, Instant now);
    Optional<ActivityRecord> findActivity(UUID activityId);
}
