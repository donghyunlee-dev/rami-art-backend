package com.ramiart.admin.lessonplan.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LessonPlanModels {
    private LessonPlanModels() {}

    public record ItemWrite(UUID id, LocalDate plannedDate, int sequence, String title, List<String> objectives,
            List<String> activities, List<String> materials, List<String> preparations, String internalNote) {}
    public record PlanWrite(long version, List<ItemWrite> items) {}
    public record PublishWrite(long version, String changeSummary, int scheduleRevision) {}
    public record Group(UUID id, String code, String name, UUID courseId, String courseName) {}
    public record Plan(UUID id, int revision, String status, long version, UUID basedOnPlanId, String changeSummary,
            UUID publishedBy, String publishedByName, Instant publishedAt, List<ItemWrite> items) {}
    public record ScheduleSnapshot(Integer revision, List<LocalDate> dates) {}
    public record InvalidItem(UUID itemId, List<String> fields) {}
    public record Diff(List<LocalDate> missingPlanDates, List<LocalDate> orphanPlanItems,
            List<InvalidItem> invalidItems) {}
    public record Permissions(boolean canRead, boolean canWrite, boolean canPublish) {}
    public record PlanView(Group classGroup, String month, Plan published, Plan draft,
            ScheduleSnapshot scheduleSnapshot, Diff diff, boolean publishable, Permissions permissions) {}
    public record PublishResult(Plan published, boolean created) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
}
