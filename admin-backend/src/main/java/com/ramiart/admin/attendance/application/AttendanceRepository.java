package com.ramiart.admin.attendance.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttendanceRepository {
    record StudentRow(UUID sessionId, UUID scheduleSlotId, LocalDate date, String className,
                      java.time.OffsetDateTime startsAt, java.time.OffsetDateTime endsAt, String sessionStatus,
                      long sessionVersion, int displayOrder, UUID studentId, String studentName,
                      String attendanceStatus, java.time.LocalTime checkInTime, String reason,
                      long attendanceVersion, java.time.OffsetDateTime updatedAt) {}
    List<StudentRow> findByDate(LocalDate date);
}
