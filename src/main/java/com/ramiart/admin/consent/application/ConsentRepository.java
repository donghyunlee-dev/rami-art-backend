package com.ramiart.admin.consent.application;

import static com.ramiart.admin.consent.application.ConsentModels.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentRepository {
    List<Policy> policies(String type);
    Optional<Policy> policy(UUID id);
    Optional<Policy> createDraft(String type,PolicyDraft draft,UUID actor);
    int updateDraft(UUID id,long version,PolicyDraft draft);
    Optional<Policy> publish(UUID id,long version,UUID actor);
    boolean studentExists(UUID studentId);
    List<ConsentTypeStatus> studentConsents(UUID studentId);
    boolean guardianBelongsTo(UUID guardianId,UUID studentId);
    Optional<Policy> currentPublishedPolicy(UUID id,String type);
    boolean privateReadyEvidence(UUID assetId);
    UUID insertConsent(UUID studentId,Policy policy,CollectConsent request,UUID actor,OffsetDateTime consentedAt);
    Optional<StudentConsent> consent(UUID id);
    boolean revoke(UUID id,long version,String reason,UUID actor);
    int expireConsents(int limit);
    Optional<String> privateEvidenceStorageKey(UUID consentId);
    IdempotencyClaim claim(String scope,UUID key,String requestHash);
    void complete(String scope,UUID key,UUID resourceId,int status);
    record IdempotencyClaim(boolean claimed,UUID resourceId){}
}
