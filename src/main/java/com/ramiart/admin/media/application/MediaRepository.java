package com.ramiart.admin.media.application;

import com.ramiart.admin.media.application.MediaModels.StoredAsset;
import com.ramiart.admin.media.application.MediaModels.AssetReference;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaRepository {
    record IdempotencyClaim(boolean claimed, UUID resourceId) {
    }

    record DeletionCandidate(UUID id, String storageKey) {
    }

    IdempotencyClaim claim(String scope, UUID key, String requestHash);

    void complete(String scope, UUID key, UUID resourceId, int status);

    Optional<StoredAsset> find(UUID id);

    List<AssetReference> references(UUID id);

    void insert(StoredAsset asset, UUID actorId);

    Optional<DeletionCandidate> lockUnreferenced(UUID id);

    int deleteLocked(UUID id);

    List<UUID> findExpiredCandidateIds(Instant now, int limit);
}

