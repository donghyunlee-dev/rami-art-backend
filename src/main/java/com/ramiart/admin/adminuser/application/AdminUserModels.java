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
    public record ChangeRoleRequest(String roleCode, long version) {}
    public record CurrentSessionImpact(boolean selfChanged, boolean permissionsChanged,
            boolean canStayOnCurrentRoute, String redirectTo) {}
    public record RoleChangeResponse(Summary user, CurrentSessionImpact currentSessionImpact) {}
    public record CreateRequest(String displayName, String email, String roleCode) {}
    public record CreatedResponse(Summary user, String temporaryPassword,
            java.time.OffsetDateTime temporaryPasswordExpiresAt) {}
    public record StatusRequest(String toStatus, String reason, long version) {}
    public record StatusResponse(Summary user, int revokedSessionCount, java.time.OffsetDateTime changedAt) {}
    public record VersionRequest(long version) {}
    public record UnlockResponse(Summary user, java.time.OffsetDateTime unlockedAt) {}
    public record TemporaryPasswordResponse(Summary user, String temporaryPassword,
            java.time.OffsetDateTime temporaryPasswordExpiresAt, int revokedSessionCount) {}
}
