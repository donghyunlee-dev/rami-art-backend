package com.ramiart.admin.enrollment.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class EnrollmentModels {
    private EnrollmentModels() {}

    public record CaseCreate(UUID inquiryId, String leadName, String phone, UUID desiredCourseId,
            UUID desiredClassGroupId) {}
    public record CaseSummary(UUID id, String leadName, String phoneLast4, String status,
            UUID desiredCourseId, String desiredCourseName, UUID desiredClassGroupId,
            String desiredClassGroupName, String nextAction, Instant updatedAt, long version) {}
    public record CasePage(List<CaseSummary> items, int page, int size, long totalElements, int totalPages) {}
    public record ActivityView(UUID id, long sequence, String type, String fromStatus, String toStatus,
            String channel, String outcome, String note, Instant occurredAt, UUID createdBy) {}
    public record CaseDetail(UUID id, UUID inquiryId, String leadName, String phone, String phoneLast4,
            String status, UUID desiredCourseId, String desiredCourseName, UUID desiredClassGroupId,
            String desiredClassGroupName, Instant trialStartsAt, Instant waitlistedAt, Integer waitlistPosition,
            UUID studentId, String lostReason, long version, int capacity, int occupancy,
            List<ActivityView> activities, List<String> actions) {}
    public record ActivityWrite(String channel, String outcome, String note, Instant occurredAt, long caseVersion) {}
    public record TrialWrite(String action, UUID classGroupId, Instant trialStartsAt, String note, long caseVersion) {}
    public record WaitlistWrite(UUID classGroupId, String note, long caseVersion) {}
    public record LostWrite(String reason, long caseVersion) {}
    public record StudentWrite(String name, LocalDate birthday, String schoolName, LocalDate joinedAt) {}
    public record GuardianWrite(String name, String relationship, String relationshipDetail, String phone,
            String email, String preferredChannel, boolean primaryContact, int displayOrder) {}
    public record EnrollWrite(long caseVersion, StudentWrite student, List<GuardianWrite> guardians,
            UUID classGroupId, List<UUID> scheduleSlotIds, LocalDate effectiveFrom, List<UUID> consentIds,
            String duplicateOverrideReason, String previewToken) {}
    public record DuplicateCandidate(UUID id, String studentName, String birthdayMonthDay, String status,
            List<String> matchedBy) {}
    public record EnrollmentPreview(String previewToken, int capacity, int occupancy, int remainingSeats,
            List<UUID> requiredConsentIds, List<UUID> missingConsentIds,
            List<DuplicateCandidate> duplicateCandidates, boolean canEnroll) {}
    public record EnrollmentResult(CaseDetail enrollmentCase, UUID studentId, List<UUID> guardianIds,
            List<UUID> scheduleAssignmentIds) {}
}
