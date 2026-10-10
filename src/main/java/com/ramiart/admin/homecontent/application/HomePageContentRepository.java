package com.ramiart.admin.homecontent.application;

import static com.ramiart.admin.homecontent.application.HomePageContentModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomePageContentRepository {
    Optional<RevisionView> findByStatus(String status);
    Optional<RevisionView> findById(UUID id);
    List<RevisionSummary> history();
    int maxRevision();
    UUID insertDraft(HomePageWrite write, UUID actor, UUID basedOnId);
    int updateDraft(UUID id, HomePageWrite write);
    void replaceChildren(UUID id, HomePageWrite write);
    void publish(UUID id, long version, UUID actor);
    List<ValidationError> validateReferences(RevisionView revision);
    Options options();
    Optional<PublicView> findPublic();
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resourceId, int status);
}
