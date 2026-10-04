package com.ramiart.admin.sitebrand.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SiteBrandModels {
    private SiteBrandModels() {}
    public record BrandWrite(Long version, String brandName, String shortName, UUID logoAssetId,
            String logoAltText, UUID faviconAssetId, UUID shareAssetId, String primaryColor,
            String accentColor, String fontPreset, String canonicalHost, String defaultTitle,
            String defaultDescription, String instagramUrl, String blogUrl) {}
    public record MediaView(String url, int width, int height) {}
    public record RevisionView(UUID id, int revision, String status, long version, String brandName,
            String shortName, UUID logoAssetId, String logoAltText, UUID faviconAssetId,
            UUID shareAssetId, String primaryColor, String accentColor, String fontPreset,
            String canonicalHost, String defaultTitle, String defaultDescription, String instagramUrl,
            String blogUrl, MediaView logo, MediaView favicon, MediaView share,
            Instant updatedAt, Instant publishedAt) {}
    public record AdminView(boolean editable, UUID draftId, String sourceStatus,
            RevisionView published, RevisionView draft) {}
    public record ContrastCheck(String pair, double ratio, boolean passed) {}
    public record RenderModel(String brandName, String shortName, String logoUrl, String faviconUrl,
            String shareUrl, String primaryColor, String accentColor, String fontPreset,
            String defaultTitle, String defaultDescription, String canonicalHost) {}
    public record Preview(RenderModel renderModel, List<ContrastCheck> contrastChecks,
            List<String> warnings, boolean publishable) {}
    public record PublicBrand(int revision, String brandName, String shortName, String logoUrl,
            String logoAltText, String faviconUrl, String shareImageUrl, String primaryColor,
            String accentColor, String fontPreset, String canonicalHost, String defaultTitle,
            String defaultDescription, String instagramUrl, String blogUrl, Instant publishedAt) {}
}
