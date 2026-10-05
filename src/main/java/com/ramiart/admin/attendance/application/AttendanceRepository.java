package com.ramiart.admin.attendance.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceRepository {
    record StudentRow(UUID sessionId, UUID scheduleSlotId, UUID classGroupId, String roomCode, LocalDate date, String className,
                      java.time.OffsetDateTime startsAt, java.time.OffsetDateTime endsAt, String sessionStatus,
                      long sessionVersion, int displayOrder, UUID studentId, String studentName,
                      String attendanceStatus, java.time.LocalTime checkInTime, String reason,
                      boolean makeupEligible, long attendanceVersion, java.time.OffsetDateTime updatedAt,
                      UUID closedBy, String closedByName, java.time.OffsetDateTime closedAt,
                      Integer presentCount, Integer lateCount, Integer absentCount, Integer excusedCount,
                      int makeupValidDays, UUID makeupCaseId, String makeupStatus) {}
    record SessionState(UUID id, String status, long version, UUID classGroupId, int makeupValidDays,
                        LocalDate date, java.time.OffsetDateTime startsAt, java.time.OffsetDateTime endsAt,
                        String className) {}
    record TargetState(UUID studentId, String studentName, int displayOrder, UUID attendanceId,
                       String status, java.time.LocalTime checkInTime, String reason, boolean makeupEligible,
                       Long attendanceVersion) {}
    record AttendanceValues(UUID id, String status, java.time.LocalTime checkInTime, String reason,
                            boolean makeupEligible, long version, java.time.OffsetDateTime updatedAt) {}
    record MakeupValue(UUID id, UUID studentId) {}
    record ClosureState(UUID closedBy, String closedByName, java.time.OffsetDateTime closedAt,
                        int targetCount, int presentCount, int lateCount, int absentCount, int excusedCount) {}
    List<StudentRow> findByDate(LocalDate date);
    Optional<List<StudentRow>> findBySessionId(UUID sessionId);
    java.util.Optional<SessionState> lockSession(UUID sessionId);
    java.util.Optional<TargetState> lockTarget(UUID sessionId, UUID studentId);
    int insertAttendance(UUID sessionId, UUID studentId, UUID actorId, AttendanceModels.AttendanceWrite write);
    int updateAttendance(UUID sessionId, UUID studentId, UUID actorId, AttendanceModels.AttendanceWrite write);
    int incrementSessionVersion(UUID sessionId, long expectedVersion);
    java.util.List<TargetState> findTargets(UUID sessionId);
    int closeSession(UUID sessionId, long expectedVersion, UUID actorId, java.time.OffsetDateTime closedAt,
                     AttendanceModels.Summary summary);
    java.util.List<MakeupValue> createMakeupCases(UUID sessionId, UUID actorId, int validDays);
    int finalizeReservedMakeupCases(UUID sessionId, UUID actorId, LocalDate today);
    java.util.Optional<AttendanceValues> findAttendance(UUID sessionId, UUID studentId);
    boolean claimIdempotency(String scope, UUID key, String requestHash);
    java.util.Optional<String> idempotencyHash(String scope, UUID key);
    void completeIdempotency(String scope, UUID key, UUID resourceId, int responseStatus);
    java.util.Optional<ClosureState> findClosure(UUID sessionId);
    String findAdminDisplayName(UUID adminUserId);
    java.util.List<UUID> findMakeupIdsForOriginSession(UUID sessionId);
}
