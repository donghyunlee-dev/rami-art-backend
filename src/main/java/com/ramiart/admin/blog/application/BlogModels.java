package com.ramiart.admin.blog.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class BlogModels {
    private BlogModels() {}
    public record Write(String title, String summary, String category, String content, UUID mediaAssetId,
            String altText, Boolean visible) {}
    public record Save(long version, String title, String summary, String category, String content,
            UUID mediaAssetId, String altText, boolean visible) {}
    public record Publish(UUID draftId, long version) {}
    public record Created(UUID postId, UUID draftId, int revision, long version, String status) {}
    public record Saved(UUID postId, UUID draftId, long version, boolean publishable, OffsetDateTime updatedAt) {}
    public record Publication(UUID postId, UUID publishedRevisionId, int revision, boolean visible,
            OffsetDateTime publishedAt) {}
    public record Media(UUID mediaAssetId, String url, String status) {}
    public record Author(UUID adminUserId, String displayName, Boolean active) {}
    public record Actions(boolean canCreateDraft, boolean canSave, boolean canPreview, boolean canPublish) {}
    public record ListActions(boolean canEdit, boolean canCreateDraft, boolean canPublish) {}
    public record Thumbnail(UUID mediaAssetId, String url, String altText, String source) {}
    public record Detail(UUID postId, UUID revisionId, UUID draftId, int revision, String status,
            String sourceStatus, boolean editable, long version, String title, String summary, String category,
            String content, Media mediaAsset, String altText, boolean visible, Author author,
            boolean publishable, Actions actions, OffsetDateTime updatedAt) {}
    public record Summary(UUID postId, String displayTitle, String displayCategory, Thumbnail displayThumbnail,
            Author author, Published published, Draft draft, ListActions actions, OffsetDateTime updatedAt) {}
    public record Published(UUID revisionId, int revision, boolean visible, OffsetDateTime publishedAt) {}
    public record Draft(UUID revisionId, int revision, OffsetDateTime updatedAt, long version, boolean publishable) {}
    public record Page<T>(int page, int size, long totalElements, int totalPages, String sort, List<T> items) {}
    public record PublicItem(UUID postId, String title, String summary, String content, String category,
            Media media, OffsetDateTime publishedAt) {}
}
