package com.ramiart.admin.blog.application;

import com.ramiart.admin.blog.application.BlogModels.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BlogRepository {
    record Revision(UUID id, UUID postId, int revision, String status, boolean visible, UUID basedOn,
            String title, String summary, String category, String content, UUID mediaId, String mediaUrl,
            String mediaStatus, String altText, UUID authorId, String authorName, boolean authorActive,
            UUID publishedBy, java.time.OffsetDateTime publishedAt, long version, java.time.OffsetDateTime updatedAt) {}
    Optional<Revision> findRevision(UUID revisionId);
    Optional<Revision> findByPostAndStatus(UUID postId, String status);
    List<Revision> listAdmin(String keyword, List<String> categories, List<String> states, String sort, int page, int size);
    long countAdmin(String keyword, List<String> categories, List<String> states);
    int nextRevision(UUID postId);
    List<Revision> listPublic(String category, String keyword, int page, int size);
    long countPublic(String category, String keyword);
    Revision insertDraft(UUID postId, UUID id, int revision, UUID basedOn, Write body, UUID actor);
    int saveDraft(UUID draftId, Save body);
    void archivePublished(UUID postId);
    void publishDraft(UUID draftId, UUID actor);
    void updateDraftReference(UUID draftId, UUID oldAssetId, UUID newAssetId);
    void publishReference(UUID draftId);
    boolean mediaReady(UUID assetId);
    Idempotency claim(String scope, UUID key, String hash);
    void complete(String scope, UUID key, UUID resourceId, int status);
    record Idempotency(boolean claimed, UUID resourceId) {}
}
