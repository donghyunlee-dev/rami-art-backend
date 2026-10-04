package com.ramiart.admin.attendance.application;

import java.time.*;
import java.util.*;

public final class AttendanceModels {
    private AttendanceModels() {}
    public record Summary(int totalCount, int presentCount, int lateCount, int absentCount, int excusedCount, int incompleteCount) {}
    public record Attendance(String status, LocalTime checkInTime, String reason, boolean makeupEligible, long version, OffsetDateTime updatedAt) {}
    public record Student(UUID studentId, String studentName, int displayOrder, Attendance attendance,
                          UUID makeupCaseId, String makeupStatus, List<String> actions) {}
    public record Session(UUID id, UUID scheduleSlotId, UUID classGroupId, String roomCode, int makeupValidDays,
                          LocalDate date, String className, OffsetDateTime startsAt, OffsetDateTime endsAt,
                          String status, long version, Summary summary, List<Student> students, Closure closure, List<String> actions) {}
    public record Day(LocalDate date, String timezone, List<Session> sessions) {}
    public record Closure(UUID closedBy, String closedByName, OffsetDateTime closedAt, Summary summary) {}
    public record AttendanceWrite(String status, LocalTime checkInTime, String reason, Boolean makeupEligible,
                                  Long version, long sessionVersion) {}
    public record CloseWrite(long version) {}
    public record SavedAttendance(Attendance attendance, long sessionVersion, Summary summary) {}
    public record ClosedSession(UUID sessionId, String status, long version, Summary summary,
                                UUID closedBy, String closedByName, OffsetDateTime closedAt,
                                int createdMakeupCount, List<UUID> makeupCaseIds) {}
}
