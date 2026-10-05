package com.ramiart.admin.classprogram.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ClassProgramModels {
    private ClassProgramModels() {}
    public record ProgramWrite(Long version, String audienceLabel, String title, String description,
            List<String> activities, UUID mediaAssetId, String altText, Boolean visible, Integer displayOrder) {}
    public record Stored(UUID id, UUID courseId, int revision, String status, boolean visible,
            String audienceLabel, String title, String description, List<String> activities,
            UUID mediaAssetId, String imageUrl, String altText, int displayOrder, long version,
            Instant updatedAt, Instant publishedAt, String courseCode, String courseName, boolean courseActive,
            String mimeType, long fileSize, int width, int height, Instant expiresAt) {}
    public record CoursePrograms(UUID id, String code, String name, boolean active, Stored currentPublished,
            Stored draft, Actions actions) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canPublish) {}
    public record ProgramRevision(UUID id, int revision, long version, String audienceLabel, String title,
            String description, List<String> activities, UUID mediaAssetId, String altText, boolean visible,
            int displayOrder, Instant updatedAt, Instant publishedAt, boolean publishable) {}
    public record CourseView(UUID id, String code, String name, boolean active) {}
    public record ProgramItem(CourseView course, ProgramRevision currentPublished, ProgramRevision draft,
            Object media, List<String> actions) {}
    public record PublishValidation(int visibleCount, List<Integer> orderConflicts, List<String> mediaErrors) {}
    public record ProgramList(List<ProgramItem> items, PublishValidation publishValidation) {}
    public record PublicProgram(String courseCode, String audienceLabel, String title, String description,
            List<String> activities, String imageUrl, String altText, int displayOrder) {}
    public record Publication(UUID id, UUID courseId, int revision, String status, long version, Instant publishedAt) {}
    public record PublishRequest(UUID draftId, Long version, String changeSummary) {}
    public record Preview(List<ProgramItem> items) {}
    public record Mutation<T>(T data, boolean replay) {}
}
