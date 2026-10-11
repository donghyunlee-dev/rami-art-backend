package com.ramiart.admin.directorprofile.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DirectorProfileModels {
    private DirectorProfileModels() {}
    public record CareerWrite(UUID id, String period, String title, Integer displayOrder, Boolean hidden) {}
    public record Career(UUID id, String period, String title, int displayOrder, boolean hidden) {}
    public record Copy(String eyebrow, String title, String description) {}
    public record Image(UUID mediaAssetId, String imageUrl, String altText, String rightsBasis,
            Boolean includesStudent, UUID studentConsentId) {}
    public record FacilityWrite(UUID id, String name, String description, Image image, Integer displayOrder, Boolean visible) {}
    public record Facility(UUID id, String name, String description, Image image, int displayOrder, boolean visible) {}
    public record AboutItemWrite(UUID id, String itemType, String iconCode, String title, String description,
            Integer displayOrder, Boolean visible) {}
    public record AboutItem(UUID id, String itemType, String iconCode, String title, String description,
            int displayOrder, boolean visible) {}
    public record PublicImage(String imageUrl, String altText) {}
    public record PublicFacility(String name, String description, PublicImage image, int displayOrder) {}
    public record Write(Long version, String name, String title, String introduction, List<CareerWrite> careers,
            Copy about, Copy philosophy, Copy facilitySection, Copy educationSection, Copy direction,
            Image portrait, List<FacilityWrite> facilities, List<AboutItemWrite> educationItems) {}
    public record StoredProfile(UUID id, int revision, String status, long version, String name,
            String title, String introduction, Copy about, Copy philosophy, Copy facilitySection,
            Copy educationSection, Copy direction, Image portrait, Instant updatedAt, Instant publishedAt) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canPreview, boolean canPublish) {}
    public record AdminView(UUID profileId, UUID draftId, int revision, String status, String sourceStatus,
            boolean editable, long version, String name, String title, String introduction,
            List<Career> careers, Copy about, Copy philosophy, Copy facilitySection, Copy educationSection,
            Copy direction, Image portrait, List<Facility> facilities, List<AboutItem> educationItems,
            boolean publishable, Actions actions, Instant updatedAt) {}
    public record PublicCareer(String period, String title, int displayOrder) {}
    public record PublicView(int revision, String name, String title, String introduction,
            List<PublicCareer> careers, Copy about, Copy philosophy, Copy facilitySection, Copy educationSection,
            Copy direction, PublicImage portrait, List<PublicFacility> facilities, List<AboutItem> educationItems,
            Instant publishedAt) {}
    public record Preview(AdminView adminPreview, PublicView publicProfile) {}
    public record PublicationRequest(UUID draftId, Long draftVersion) {}
    public record Publication(UUID profileId, int revision, String status, long version, Instant publishedAt) {}
    public record ConsentOption(UUID id, String label) {}
    public record Mutation<T>(T data, boolean replay) {}
}
