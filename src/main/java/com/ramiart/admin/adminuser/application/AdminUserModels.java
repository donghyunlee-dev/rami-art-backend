package com.ramiart.admin.adminuser.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminUserModels {
    private AdminUserModels() {}

    public record Role(String code, String name) {}
    public record Summary(UUID id, String displayName, String email, Role role, String status,
            OffsetDateTime lastLoginAt, OffsetDateTime lockedUntil, boolean passwordMustChange,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, long version, boolean self,
            List<String> actions) {}
    public record ListResponse(List<Summary> items, int page, int size, long totalElements,
            int totalPages, String sort) {}
    public record Query(String keyword, String role, String status, int page, int size, String sort) {}
}
