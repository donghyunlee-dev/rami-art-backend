package com.ramiart.admin.lessonplan.application;

import static com.ramiart.admin.lessonplan.application.LessonPlanModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LessonPlanRepository {
    Optional<Group> group(UUID classGroupId);
    Optional<Group> groupForPlan(UUID planId);
    Optional<String> monthForPlan(UUID planId);
    String adminDisplayName(UUID actorId);
    boolean isOwner(UUID actorId);
    boolean managesMonth(UUID actorId, UUID classGroupId, LocalDate monthStart, LocalDate monthEnd);
    Optional<ScheduleSnapshot> scheduleSnapshot(UUID classGroupId, String month, boolean lock);
    Optional<Plan> findPlan(UUID classGroupId, String month, String status, boolean lock);
    Optional<Plan> findPlanById(UUID planId, boolean lock);
    int nextRevision(UUID classGroupId, String month);
    Plan insertDraft(UUID classGroupId, String month, int revision, UUID basedOnPlanId, UUID actorId);
    int updateDraft(UUID planId, long version);
    void replaceItems(UUID planId, List<ItemWrite> items);
    int archivePublished(UUID classGroupId, String month);
    int publish(UUID planId, long version, String changeSummary, UUID actorId, Instant publishedAt);
    boolean claimIdempotency(String scope, UUID key, String hash);
    Optional<String> idempotencyHash(String scope, UUID key);
    Optional<UUID> idempotencyResource(String scope, UUID key);
    void completeIdempotency(String scope, UUID key, UUID resourceId, int status);
}
