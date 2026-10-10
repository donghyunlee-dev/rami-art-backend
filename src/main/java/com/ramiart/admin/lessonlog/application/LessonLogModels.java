package com.ramiart.admin.lessonlog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LessonLogModels {
    private LessonLogModels() {}

    public record Session(UUID id, UUID classGroupId, String classGroupName, String status,
            LocalDate date, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID planItemId,
            String planItemLinkIssue) {}
    public record Target(UUID studentId, String studentName, String attendanceStatus, int displayOrder) {}
    public record PlanItem(UUID id, String title, List<String> activities, List<String> materials) {}
    public record StudentRecord(UUID id, UUID studentId, String attendanceStatusSnapshot, String participation,
            String progressNote, String observation, String absenceNote, List<UUID> artworkAssetIds) {}
    public record Revision(UUID id, int revision, String status, String amendReason,
            UUID createdBy, String createdByName, Instant createdAt, UUID finalizedBy,
            String finalizedByName, Instant finalizedAt) {}
    public record Log(UUID id, UUID sessionId, int revision, String status, UUID basedOnLogId,
            UUID planItemId, String actualTitle, List<String> activities, List<String> materials,
            String changeReason, String overallNote, String amendReason, long version,
            UUID createdBy, String createdByName, Instant createdAt, UUID finalizedBy,
            String finalizedByName, Instant finalizedAt, List<StudentRecord> studentRecords) {}
    public record View(Session session, List<String> assignedStaff, List<Target> attendanceTargets,
            PlanItem planItem, Log currentLog, List<Revision> revisionHistory,
            List<UUID> missingRequiredStudentIds, boolean finalizable) {}
    public record ListItem(UUID sessionId, LocalDate date, String className, OffsetDateTime startsAt,
            OffsetDateTime endsAt, String assignedStaff, String planTitle, int targetCount,
            String logStatus, Integer revision) {}
    public record LessonLogPage(int page, int size, long totalElements, int totalPages, List<ListItem> items) {}

    public record StudentRecordWrite(UUID studentId, String attendanceStatusSnapshot, String participation,
            String progressNote, String observation, String absenceNote, List<UUID> artworkAssetIds) {}
    public record SaveWrite(long version, UUID planItemId, String actualTitle, List<String> activities,
            List<String> materials, String changeReason, String overallNote,
            List<StudentRecordWrite> studentRecords) {}
    public record FinalizeWrite(long version) {}
    public record AmendmentWrite(String amendReason) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
}
