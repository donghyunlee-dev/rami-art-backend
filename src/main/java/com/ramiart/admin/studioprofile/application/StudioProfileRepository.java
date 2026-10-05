package com.ramiart.admin.studioprofile.application;

import com.ramiart.admin.studioprofile.application.StudioProfileModels.StoredProfile;
import com.ramiart.admin.studioprofile.application.StudioProfileModels.Write;
import java.util.Optional;
import java.util.UUID;

public interface StudioProfileRepository {
    record Claim(boolean claimed, UUID resourceId) {}
    Optional<StoredProfile> findByStatus(String status);
    Optional<StoredProfile> findById(UUID id);
    Optional<StoredProfile> findByIdForUpdate(UUID id);
    int maxRevision();
    UUID insertDraft(Write write, UUID actor, UUID basedOn);
    int updateDraft(UUID id, Write write);
    void publish(UUID id, long version, UUID actor);
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resourceId, int responseStatus);
}
