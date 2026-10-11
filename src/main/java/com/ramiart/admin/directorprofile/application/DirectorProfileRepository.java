package com.ramiart.admin.directorprofile.application;

import com.ramiart.admin.directorprofile.application.DirectorProfileModels.Career;
import com.ramiart.admin.directorprofile.application.DirectorProfileModels.StoredProfile;
import com.ramiart.admin.directorprofile.application.DirectorProfileModels.Write;
import static com.ramiart.admin.directorprofile.application.DirectorProfileModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DirectorProfileRepository {
    record Claim(boolean claimed, UUID resourceId) {}
    Optional<StoredProfile> findByStatus(String status);
    Optional<StoredProfile> findById(UUID id);
    Optional<StoredProfile> findByIdForUpdate(UUID id);
    List<Career> careers(UUID profileId);
    List<Facility> facilities(UUID profileId);
    List<AboutItem> aboutItems(UUID profileId);
    List<ConsentOption> consentOptions();
    boolean imageRightsValid(UUID profileId);
    boolean imageCurrentlyPublic(UUID assetId, boolean includesStudent, UUID consentId, boolean validateConsent);
    int maxRevision();
    UUID insertDraft(Write write, UUID actor, UUID basedOn);
    int updateDraft(UUID id, Write write);
    void replaceCareers(UUID profileId, List<Career> careers);
    void replaceAbout(UUID profileId, List<Facility> facilities, List<AboutItem> items);
    void publish(UUID id, long version, UUID actor);
    Claim claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resourceId, int status);
}
