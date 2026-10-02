package com.ramiart.admin.student.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StudentModels {
    private StudentModels() {}

    public record PageInfo(int number, int size, long totalElements, int totalPages, boolean first, boolean last) {}
    public record StudentPage(List<StudentSummary> items, PageInfo page, Map<String, Object> applied) {}
    public record StudentSummary(UUID id, String studentName, String schoolName, String birthdayMonthDay,
            int lessonCountPerWeek, String status, LocalDate joinedAt, long version, List<String> actions) {}
    public record GuardianView(UUID id, String name, String relationship, String relationshipDetail,
            String phone, String maskedPhone, String email, String maskedEmail, String preferredChannel,
            boolean notificationAvailable, boolean primaryContact, int displayOrder) {}
    public record StatusSummary(Instant lastChangedAt, String lastReason) {}
    public record StudentDetail(UUID id, String studentName, String schoolName, LocalDate birthday,
            int lessonCountPerWeek, String status, LocalDate joinedAt, long version,
            List<GuardianView> guardians, StatusSummary statusSummary, List<String> actions) {}
    public record GuardianWrite(UUID id, String name, String relationship, String relationshipDetail,
            String phone, String email, String preferredChannel, boolean primaryContact, int displayOrder) {}
    public record StudentCreate(String studentName, LocalDate birthday, String schoolName, LocalDate joinedAt,
            boolean duplicateConfirmed, List<GuardianWrite> guardians) {}
    public record StudentUpdate(String studentName, LocalDate birthday, String schoolName, LocalDate joinedAt,
            long version, List<GuardianWrite> guardians) {}
    public record StatusWrite(String toStatus, LocalDate effectiveDate, String reason, long version, String previewToken) {}
    public record StatusPreview(String previewToken, String fromStatus, String toStatus, LocalDate effectiveDate, Impacts impacts) {}
    public record Impacts(int futureAttendanceTargetCount, int activeAssignmentCount, int openBillingCount,
            List<String> automaticChanges) {}
    public record StatusChange(UUID historyId, String fromStatus, String toStatus, LocalDate effectiveDate,
            Instant changedAt, long version, Impacts impacts) {}
    public record CreatedBy(UUID id, String displayName) {}
    public record NoteView(UUID id, UUID studentId, String content, CreatedBy createdBy,
            Instant createdAt, Instant updatedAt, boolean edited, long version, List<String> actions) {}
    public record NotePage(List<NoteView> items, NotePageInfo page) {}
    public record NotePageInfo(int size, String nextCursor) {}
    public record NoteCreate(String content) {}
    public record NoteUpdate(String content, long version) {}
    public record NoteHide(String reason, long version) {}
}
