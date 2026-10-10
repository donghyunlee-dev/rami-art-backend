package com.ramiart.admin.galleryartwork.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GalleryArtworkModels {
    private GalleryArtworkModels() {}
    public record Content(String title,UUID courseId,String audienceLabel,String medium,String description,
            UUID mediaAssetId,String altText,UUID studentConsentId,String consentExemptionReason,
            Boolean visible,Boolean featured,Integer featuredOrder) {}
    public record Write(Long version,String title,UUID courseId,String audienceLabel,String medium,String description,
            UUID mediaAssetId,String altText,UUID studentConsentId,String consentExemptionReason,
            Boolean visible,Boolean featured,Integer featuredOrder,String reauthToken) {}
    public record Stored(UUID id,UUID artworkId,int revision,String status,boolean visible,String title,UUID courseId,
            String courseCode,String courseName,boolean courseActive,String audienceLabel,String medium,String description,
            UUID mediaAssetId,String imageUrl,String fileName,String mimeType,long fileSize,int width,int height,Instant expiresAt,
            String altText,UUID consentId,String consentStatus,String maskedStudentLabel,Integer policyRevision,
            String exemptionReason,boolean featured,Integer featuredOrder,long version,Instant updatedAt,Instant publishedAt) {}
    public record Course(UUID id,String code,String name,boolean active) {}
    public record ListItem(UUID artworkId,String title,Course course,String medium,Media media,
            PublishedSummary published,DraftSummary draft,List<String> actions) {}
    public record Media(UUID id,String thumbnailUrl,String status) {}
    public record PublishedSummary(int revision,boolean visible,Instant publishedAt,boolean featured,Integer featuredOrder) {}
    public record DraftSummary(int revision,boolean publishable,Instant updatedAt,boolean featured) {}
    public record Page(List<ListItem> items,PageInfo page) {}
    public record PageInfo(int number,int size,long totalElements,int totalPages) {}
    public record Consent(String id,String maskedStudentLabel,String courseName,int policyRevision,Instant consentedAt,Instant expiresAt,String status,long version) {}
    public record ConsentPage(List<Consent> items) {}
    public record Placement(String placement,String title,String imageUrl,String description,String altText) {}
    public record Preview(List<Placement> placements,UUID artworkId,long draftVersion) {}
    public record Detail(UUID artworkId,String sourceStatus,boolean editable,Revision draft,Revision published,MediaAsset media,ConsentStatus consent,List<String> actions) {}
    public record Revision(UUID id,int revision,long version,String title,UUID courseId,String audienceLabel,String medium,
            String description,UUID mediaAssetId,String altText,UUID studentConsentId,String consentExemptionReason,
            boolean visible,boolean featured,Integer featuredOrder,boolean publishable,Instant updatedAt,Instant publishedAt) {}
    public record MediaAsset(UUID id,String publicUrl,String fileName,String mimeType,long fileSize,int width,int height,String status,Instant expiresAt) {}
    public record ConsentStatus(String status,String studentLabel,Integer policyRevision) {}
    public record PublicArtwork(UUID artworkId,String courseCode,String audienceLabel,String title,String medium,
            String description,String imageUrl,String altText,boolean featured,Integer featuredOrder,Instant publishedAt) {}
    public record PublicPage(List<PublicArtwork> items,PageInfo page) {}
    public record Mutation<T>(T data,boolean replay) {}
    public record PublicationRequest(UUID draftId,Long draftVersion,String consentExemptionReason,String reauthToken) {}
}
