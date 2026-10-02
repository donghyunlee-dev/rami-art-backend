package com.ramiart.admin.staff.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class StaffModels {

    private StaffModels() {
    }

    public record StaffPage(List<StaffSummary> content, long totalElements, int page, int size) {
    }

    public record StaffSummary(
            UUID id, String staffCode, String displayName, String jobTitle, String phoneLast4,
            LocalDate hiredOn, LocalDate leftOn, String status, List<String> currentClassGroups, long version) {
    }

    public record StaffDetail(
            UUID id, String staffCode, String name, String displayName, String jobTitle, String phone,
            LocalDate hiredOn, LocalDate leftOn, String status, UUID adminUserId,
            String adminUserDisplayName, long version, List<AssignmentView> assignments) {
    }

    public record AssignmentView(
            UUID id, UUID classGroupId, String classGroupCode, String classGroupName, String courseName,
            String role, LocalDate effectiveFrom, LocalDate effectiveTo, long version, String derivedStatus) {
    }

    public record StaffWrite(
            String staffCode, String name, String displayName, String jobTitle, String phone,
            LocalDate hiredOn, LocalDate leftOn, String status, UUID adminUserId,
            Long version, Boolean closeFutureAssignments) {
    }

    public record AssignmentWrite(
            UUID id, UUID classGroupId, String role, LocalDate effectiveFrom,
            LocalDate effectiveTo, Long version) {
    }

    public record AssignmentSetWrite(long staffVersion, List<AssignmentWrite> assignments) {
    }

    public record StaffOptions(List<ClassGroupOption> classGroups, List<AdminUserOption> adminUsers) {
    }

    public record ClassGroupOption(
            UUID id, String code, String name, String courseName,
            LocalDate startsOn, LocalDate endsOn, String status) {
    }

    public record AdminUserOption(UUID id, String displayName, String email) {
    }
}
