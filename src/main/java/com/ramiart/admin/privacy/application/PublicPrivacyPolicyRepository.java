package com.ramiart.admin.privacy.application;

import static com.ramiart.admin.privacy.application.PublicPrivacyPolicyModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicPrivacyPolicyRepository {
    record Claim(boolean claimed, UUID resourceId) {}

    List<Policy> findAll();
    Optional<Policy> findById(UUID id);
    Optional<Policy> findByIdForUpdate(UUID id);
    Optional<Policy> findPublished();
    Optional<Policy> findPublishedByVersion(String versionCode);
    int nextRevision();
    UUID insertDraft(Write write, UUID actor, UUID basedOn);
    int updateDraft(UUID id, Write write);
    void publish(UUID id, long version, UUID actor);
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID id, int status);
}
