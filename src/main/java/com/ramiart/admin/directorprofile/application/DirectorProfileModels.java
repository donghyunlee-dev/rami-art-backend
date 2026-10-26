package com.ramiart.admin.directorprofile.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DirectorProfileModels {
    private DirectorProfileModels() {}
    public record CareerWrite(UUID id, String title, Integer displayOrder) {}
    public record Career(UUID id, String title, int displayOrder) {}
    public record Image(UUID mediaAssetId, String imageUrl, String altText) {}
    public record StudioImageWrite(UUID id, UUID mediaAssetId, String imageUrl, String altText,
            String description, Integer displayOrder) {}
    public record StudioImage(UUID id, UUID mediaAssetId, String imageUrl, String altText,
            String description, int displayOrder) {}
    public record PublicImage(String imageUrl, String altText) {}
    public record PublicStudioImage(String imageUrl, String altText, String description, int displayOrder) {}
    public record Write(Long version, String name, String introduction, String studioIntroduction,
            List<CareerWrite> careers, Image portrait, List<StudioImageWrite> studioImages) {}
    public record StoredProfile(UUID id, int revision, String status, long version, String name,
            String introduction, String studioIntroduction, Image portrait, Instant updatedAt, Instant publishedAt) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canPreview, boolean canPublish) {}
    public record AdminView(UUID profileId, UUID draftId, int revision, String status, String sourceStatus,
            boolean editable, long version, String name, String introduction, String studioIntroduction,
            List<Career> careers, Image portrait, List<StudioImage> studioImages, boolean publishable,
            Actions actions, Instant updatedAt) {}
    public record PublicCareer(String title, int displayOrder) {}
    public record PublicView(int revision, String name, String introduction, String studioIntroduction,
            List<PublicCareer> careers, PublicImage portrait, List<PublicStudioImage> studioImages,
            Instant publishedAt) {}
    public record Preview(AdminView adminPreview, PublicView publicProfile) {}
    public record PublicationRequest(UUID draftId, Long draftVersion) {}
    public record Publication(UUID profileId, int revision, String status, long version, Instant publishedAt) {}
    public record Mutation<T>(T data, boolean replay) {}
}
