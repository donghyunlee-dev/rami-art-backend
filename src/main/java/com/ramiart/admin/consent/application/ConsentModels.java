package com.ramiart.admin.consent.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ConsentModels {
    private ConsentModels(){}
    public record Policy(UUID id,String type,int revision,String status,String title,String body,boolean required,
            Integer validDays,boolean evidenceRequired,long version,UUID createdBy,OffsetDateTime createdAt,
            UUID publishedBy,OffsetDateTime publishedAt){}
    public record PolicyDraft(String title,String body,boolean required,Integer validDays,boolean evidenceRequired){}
    public record StudentConsent(UUID id,String type,UUID policyId,int revision,String status,String method,
            UUID guardianContactId,OffsetDateTime consentedAt,LocalDate expiresOn,UUID evidenceAssetId,long version){}
    public record Uses(int publicArtworkCount,int queuedOptionalNotificationCount){}
    public record PublicArtworkReference(UUID artworkId,UUID revisionId,int revision,String title,
            UUID mediaAssetId,String altText,boolean currentlyPublic){}
    public record ConsentTypeStatus(String type,Policy currentPolicy,StudentConsent currentConsent,String status,
            LocalDate expiresOn,Uses uses,List<String> actions){}
    public record StudentConsents(UUID studentId,List<ConsentTypeStatus> items){}
    public record CollectConsent(@JsonProperty("policyId") UUID policyId,
            @JsonProperty("guardianContactId") UUID guardianContactId,
            @JsonProperty("method") String method,
            @JsonProperty("consentedAt") OffsetDateTime consentedAt,
            @JsonProperty("evidenceAssetId") UUID evidenceAssetId){}
    public record RevokeRequest(long version,String reason){}
    public record EvidenceUrl(String url,OffsetDateTime expiresAt){}
    public record RequestMetadata(String requestId,String ipAddress,String userAgent){}
}
