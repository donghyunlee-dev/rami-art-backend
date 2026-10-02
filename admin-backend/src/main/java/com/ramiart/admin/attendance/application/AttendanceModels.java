package com.ramiart.admin.attendance.application;

import java.time.*;
import java.util.*;

public final class AttendanceModels {
    private AttendanceModels() {}
    public record Summary(int totalCount, int presentCount, int lateCount, int absentCount, int excusedCount, int incompleteCount) {}
    public record Attendance(String status, LocalTime checkInTime, String reason, long version, OffsetDateTime updatedAt) {}
    public record Student(UUID studentId, String studentName, int displayOrder, Attendance attendance, List<String> actions) {}
    public record Session(UUID id, UUID scheduleSlotId, LocalDate date, String className, OffsetDateTime startsAt, OffsetDateTime endsAt,
                          String status, long version, Summary summary, List<Student> students, List<String> actions) {}
    public record Day(LocalDate date, String timezone, List<Session> sessions) {}
}
