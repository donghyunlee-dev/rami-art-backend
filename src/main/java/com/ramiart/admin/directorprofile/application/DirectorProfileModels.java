package com.ramiart.admin.directorprofile.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DirectorProfileModels {
    private DirectorProfileModels() {}
    public record CareerWrite(UUID id, String period, String title, Integer displayOrder, Boolean hidden) {}
    public record Career(UUID id, String period, String title, int displayOrder, boolean hidden) {}
    public record Write(Long version, String name, String title, String introduction, List<CareerWrite> careers) {}
    public record StoredProfile(UUID id, int revision, String status, long version, String name,
            String title, String introduction, Instant updatedAt, Instant publishedAt) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canPreview, boolean canPublish) {}
    public record AdminView(UUID profileId, UUID draftId, int revision, String status, String sourceStatus,
            boolean editable, long version, String name, String title, String introduction,
            List<Career> careers, boolean publishable, Actions actions, Instant updatedAt) {}
    public record PublicCareer(String period, String title, int displayOrder) {}
    public record PublicView(int revision, String name, String title, String introduction,
            List<PublicCareer> careers, Instant publishedAt) {}
    public record Preview(AdminView adminPreview, PublicView publicProfile) {}
    public record PublicationRequest(UUID draftId, Long draftVersion) {}
    public record Publication(UUID profileId, int revision, String status, long version, Instant publishedAt) {}
    public record Mutation<T>(T data, boolean replay) {}
}
