package com.ramiart.admin.tuition.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TuitionPolicyModels {
    private TuitionPolicyModels() {}
    public record Item(UUID id, int lessonCountPerWeek, long monthlyAmount) {}
    public record Policy(UUID id, UUID draftId, int year, int revision, String status, String sourceStatus,
            boolean editable, long version, int defaultDueDay, UUID basedOnPolicyId, Instant publishedAt,
            UUID publishedBy, List<Item> items, Validation validation, Actions actions) {}
    public record Validation(boolean publishable, List<Integer> missingLessonCounts, List<Integer> duplicateLessonCounts) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canEdit, boolean canPublish) {}
    public record Write(long version, int defaultDueDay, List<Item> items) {}
    public record Publish(UUID draftId, long draftVersion) {}
    public record AssignmentOptions(int year, List<Item> items) {}
}
