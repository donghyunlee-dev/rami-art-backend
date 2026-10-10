package com.ramiart.admin.enrollment.application;

import com.ramiart.admin.enrollment.application.EnrollmentModels.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentRepository {
    record InquiryLead(UUID id, byte[] nameCiphertext, byte[] phoneCiphertext, UUID courseId) {}
    record CaseRecord(UUID id, UUID inquiryId, byte[] nameCiphertext, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, String status, UUID courseId, String courseName, UUID groupId, String groupName,
            Instant trialAt, Instant waitlistedAt, UUID studentId, String lostReason, long version,
            Instant updatedAt, int capacity, int occupancy, UUID assigneeId, String assigneeName) {}
    record ActivityRecord(UUID id,long sequence,String type,String fromStatus,String toStatus,String channel,
            String outcome,byte[] noteCiphertext,Instant occurredAt,UUID createdBy) {}
    record ClassLock(UUID id,UUID courseId,int capacity,String status,LocalDate startsOn,LocalDate endsOn,int occupancy) {}
    record Claim(boolean claimed,UUID resourceId) {}
    record CaseRecordsPage(List<CaseRecord> items,long total) {}
    record StoredGuardian(UUID id,GuardianWrite value,byte[] phoneCiphertext,String phoneHash,String last4,
            byte[] emailCiphertext,String emailHash,String emailDomain) {}

    Optional<InquiryLead> findInquiry(UUID id);
    Optional<UUID> findCaseByInquiry(UUID id);
    CaseRecordsPage findPage(List<String> statuses,UUID courseId,Instant from,Instant to,int page,int size);
    Optional<CaseRecord> findCase(UUID id,boolean lock);
    List<AssigneeOption> findAssignees();
    boolean isActiveAssignee(UUID id);
    int updateAssignee(UUID id,long version,UUID assigneeId,UUID actor);
    List<ActivityRecord> findActivities(UUID caseId);
    int waitlistPosition(UUID caseId,UUID groupId,Instant at);
    void insertCase(UUID id,UUID inquiryId,byte[] name,byte[] phone,String phoneHash,String last4,
            UUID courseId,UUID groupId,UUID actor);
    int updateState(UUID id,long version,String expected,String next,UUID courseId,UUID groupId,
            Instant trialAt,Instant waitlistedAt,String lostReason,UUID studentId,UUID actor);
    UUID insertActivity(UUID caseId,String type,String from,String to,String channel,String outcome,
            byte[] note,Instant occurredAt,UUID actor);
    Optional<ClassLock> lockClass(UUID groupId,LocalDate effectiveFrom);
    List<UUID> activeSlotIds(UUID groupId,List<UUID> requested);
    List<UUID> requiredConsentIds();
    List<DuplicateCandidate> duplicateCandidates(String normalizedName,LocalDate birthday,List<String> phoneHashes);
    void insertStudent(UUID id,StudentWrite student,String nameSearch,UUID actor);
    void insertGuardian(UUID studentId,StoredGuardian guardian);
    void insertInitialStatus(UUID studentId,LocalDate joinedAt,UUID actor);
    UUID insertAssignment(UUID studentId,UUID slotId,LocalDate from,UUID actor);
    void insertConsent(UUID studentId,UUID guardianId,UUID policyId,UUID actor);
    List<UUID> guardianIds(UUID studentId);
    List<UUID> assignmentIds(UUID studentId);
    Claim claim(String scope,UUID key,String hash);
    void complete(String scope,UUID key,UUID resourceId,int status);
}
