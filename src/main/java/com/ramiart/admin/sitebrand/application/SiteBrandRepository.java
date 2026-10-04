package com.ramiart.admin.sitebrand.application;

import com.ramiart.admin.sitebrand.application.SiteBrandModels.*;
import java.util.Optional;
import java.util.UUID;

public interface SiteBrandRepository {
    record Claim(boolean claimed, UUID resourceId) {}
    Optional<RevisionView> findByStatus(String status);
    Optional<RevisionView> findById(UUID id);
    Optional<PublicBrand> findPublic();
    int maxRevision();
    UUID insertDraft(BrandWrite write, UUID actor, UUID basedOn);
    int updateDraft(UUID id, BrandWrite write);
    void replaceDraftReferences(UUID id, BrandWrite write);
    void publish(UUID id, long version, UUID actor);
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resourceId, int status);
    String mediaUrl(UUID id);
    boolean faviconSquare(UUID id);
}
