package com.ramiart.admin.homecontent.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HomePageContentModels {
    private HomePageContentModels() {}

    public record Hero(String title, String description, UUID mediaAssetId, String imageUrl,
            String altText, String ctaLabel, String ctaTarget) {}
    public record StrengthWrite(String iconCode, String title, String description, int displayOrder) {}
    public record Strength(String iconCode, String title, String description, int displayOrder) {}
    public record SectionWrite(String sectionKey, boolean visible, int displayOrder) {}
    public record Section(String sectionKey, boolean visible, int displayOrder) {}
    public record ReferencesWrite(List<UUID> courses, List<UUID> artworks, List<UUID> posts) {}
    public record References(List<HomeReference> courses, List<HomeReference> artworks, List<HomeReference> posts) {}
    public record HomeReference(UUID id, String label, int sourceRevision, int displayOrder) {}
    public record HomePageWrite(Long version, Hero hero, List<StrengthWrite> strengths,
            List<SectionWrite> sections, ReferencesWrite references) {}
    public record RevisionView(UUID id, int revision, String status, long version, Hero hero,
            List<Strength> strengths, List<Section> sections, References references,
            Instant updatedAt, Instant publishedAt) {}
    public record RevisionSummary(UUID id, int revision, String status, Instant publishedAt) {}
    public record AdminView(RevisionView published, RevisionView draft, List<RevisionSummary> history) {}
    public record Option(UUID id, String label, int revision) {}
    public record Options(List<Option> courses, List<Option> artworks, List<Option> posts) {}
    public record ValidationError(String type, UUID targetId, String errorCode, String message) {}
    public record Preview(RevisionView content, List<ValidationError> validationErrors, boolean publishable) {}
    public record PublicStrength(String iconCode, String title, String description, int displayOrder) {}
    public record PublicHero(String title, String description, String imageUrl, String altText, String ctaLabel, String href) {}
    public record PublicSection(String sectionKey, int displayOrder) {}
    public record PublicCourse(UUID courseId, int revision, String courseCode, String audienceLabel,
            Integer sessionDurationMinutes, Integer weeklySessions,
            String title, String description, List<String> activities, String imageUrl, String altText, int displayOrder) {}
    public record PublicArtwork(UUID artworkId, int revision, String courseCode, String audienceLabel,
            String title, String medium, String description, String imageUrl, String altText, int displayOrder) {}
    public record PublicPost(UUID postId, int revision, String title, String summary, String category,
            String imageUrl, String altText, Instant publishedAt, int displayOrder) {}
    public record PublicView(int revision, PublicHero hero, List<PublicStrength> strengths, List<PublicSection> sections,
            List<PublicCourse> courses, List<PublicArtwork> artworks, List<PublicPost> posts, Instant publishedAt) {}
    public record Publication(UUID id, int revision, String status, Instant publishedAt) {}
    public record Mutation<T>(T data, boolean replay) {}
    public record Claim(boolean claimed, UUID resourceId) {}
}
