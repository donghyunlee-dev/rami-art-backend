package com.ramiart.admin.galleryartwork.application;

import static com.ramiart.admin.galleryartwork.application.GalleryArtworkModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GalleryArtworkRepository {
    record Claim(boolean claimed,UUID resourceId) {}
    Page list(String keyword,List<UUID> courses,List<String> states,String featured,String sort,int page,int size);
    Optional<Stored> find(UUID artworkId,String status);
    Optional<Stored> findRevision(UUID id);
    UUID create(UUID artworkId,int revision,UUID actor,Content content,UUID basedOn);
    int update(UUID id,Write content);
    boolean consentValid(UUID consentId);
    boolean owner(UUID actor);
    void replaceReferences(UUID id,UUID asset);
    void publish(UUID artworkId,UUID draftId,long version,UUID actor,Content content);
    List<ListItem> previewList();
    List<GalleryArtworkModels.PublicArtwork> publicList(List<String> courseCodes,Boolean featured,int page,int size);
    long publicCount(List<String> courseCodes,Boolean featured);
    List<Consent> consentOptions(String keyword,int page,int size);
    Claim claim(String scope,UUID key,String hash);
    void complete(String scope,UUID key,UUID resource,int responseStatus);
    int maxRevision(UUID artworkId);
}
