package com.ramiart.admin.studioprofile.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StudioProfileModels {
    private StudioProfileModels() {}

    public record BusinessHour(int day, boolean closed, String open, String close) {}
    public record Write(Long version, String studioName, String phone, String email, String address,
            String addressDetail, BigDecimal latitude, BigDecimal longitude,
            List<BusinessHour> businessHours, String closedDays, String transitGuide, String parkingGuide) {}
    public record StoredProfile(UUID id, Integer revision, String status, Long version,
            String studioName, String phone, String email, String address, String addressDetail,
            BigDecimal latitude, BigDecimal longitude, String businessHoursJson, String closedDays,
            String transitGuide, String parkingGuide, Instant updatedAt, Instant publishedAt) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canPreview,
            boolean canEdit, boolean canPublish) {}
    public record AdminView(UUID profileId, UUID draftId, Integer revision, String status,
            String sourceStatus, boolean editable, Long version, String studioName, String phone,
            String email, String address, String addressDetail, BigDecimal latitude,
            BigDecimal longitude, List<BusinessHour> businessHours, String closedDays,
            String transitGuide, String parkingGuide, boolean publishable, Actions actions,
            Instant updatedAt) {}
    public record Coordinates(BigDecimal latitude, BigDecimal longitude) {}
    public record PublicView(int revision, String studioName, String phone, String email,
            String address, String addressDetail, Coordinates coordinates,
            List<BusinessHour> businessHours, String closedDays, String transitGuide,
            String parkingGuide, Instant publishedAt) {}
    public record PlacementPreview(String placement, int revision, String studioName, String phone,
            String email, String address, String addressDetail, Coordinates coordinates,
            List<BusinessHour> businessHours, String closedDays, String transitGuide,
            String parkingGuide) {}
    public record Preview(UUID draftId, long version, List<PlacementPreview> placements) {}
    public record Publication(UUID profileId, int revision, String status, long version, Instant publishedAt) {}
    public record PublicationRequest(UUID draftId, Long draftVersion) {}
    public record Mutation<T>(T data, boolean replay) {}
}
