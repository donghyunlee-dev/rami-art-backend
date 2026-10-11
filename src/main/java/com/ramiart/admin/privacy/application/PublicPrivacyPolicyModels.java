package com.ramiart.admin.privacy.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PublicPrivacyPolicyModels {
    private PublicPrivacyPolicyModels() {}

    public record Write(long version, String versionCode, String title, String collectionItems, String purpose,
            int retentionMonths, String retentionAnchor, String contactEmail, LocalDate effectiveOn) {}
    public record Policy(UUID id, int revision, String status, String versionCode, String title,
            String collectionItems, String purpose, int retentionMonths, String retentionAnchor,
            String contactEmail, LocalDate effectiveOn, long version, OffsetDateTime createdAt,
            UUID publishedBy, OffsetDateTime publishedAt) {}
    public record PublicView(UUID id, int revision, String versionCode, String title, String collectionItems,
            String purpose, int retentionMonths, String retentionAnchor, String contactEmail,
            LocalDate effectiveOn, OffsetDateTime publishedAt) {}
    public record Policies(List<Policy> revisions) {}
    public record Preview(Policy draft, Policy published, int internalRetentionMonths,
            String internalRetentionAnchor, boolean retentionMismatch, boolean consentRenewalReviewRequired) {}
    public record PublicationRequest(long version, boolean retentionMismatchReviewed) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
}
