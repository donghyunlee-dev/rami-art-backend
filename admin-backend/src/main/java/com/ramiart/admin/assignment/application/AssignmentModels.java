package com.ramiart.admin.assignment.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class AssignmentModels {
    private AssignmentModels() {}

    public record StudentSummary(UUID id, String name, String status) {}
    public record ScheduleView(boolean available, String title, int dayOfWeek, LocalTime startTime,
            LocalTime endTime, String roomCode) {}
    public record AttendanceImpact(LocalDate firstSnapshotDate, LocalDate lastSnapshotDate,
            LocalDate minimumEffectiveFrom, LocalDate minimumEffectiveTo) {}
    public record AssignmentView(UUID id, UUID studentId, UUID scheduleSlotId, ScheduleView schedule,
            LocalDate effectiveFrom, LocalDate effectiveTo, String status, String endedReason, long version,
            AttendanceImpact attendanceImpact, List<String> actions) {}
    public record AssignmentGroups(List<AssignmentView> active, List<AssignmentView> scheduled,
            List<AssignmentView> ended) {}
    public record AssignmentPage(StudentSummary student, AssignmentGroups groups) {}
    public record ConflictView(UUID assignmentId, String scheduleLabel, int dayOfWeek, LocalTime startTime,
            LocalTime endTime, LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record CandidateView(UUID scheduleSlotId, boolean available, String title, int dayOfWeek,
            LocalTime startTime, LocalTime endTime, String roomCode, List<ConflictView> conflicts) {}
    public record CreateCommand(UUID scheduleSlotId, LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record UpdateCommand(LocalDate effectiveFrom, LocalDate effectiveTo, String endedReason, long version) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
}
