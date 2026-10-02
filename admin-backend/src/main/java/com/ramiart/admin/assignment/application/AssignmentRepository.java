package com.ramiart.admin.assignment.application;

import static com.ramiart.admin.assignment.application.AssignmentModels.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository {
    record AssignmentRecord(UUID id, UUID studentId, UUID scheduleSlotId, ScheduleView schedule,
            LocalDate effectiveFrom, LocalDate effectiveTo, String endedReason, long version,
            LocalDate firstSnapshot, LocalDate lastSnapshot) {}
    record StudentRecord(UUID id, String name, String status) {}
    record Claim(boolean claimed, UUID resourceId) {}

    Optional<StudentRecord> findStudent(UUID id, boolean lock);
    List<AssignmentRecord> findByStudent(UUID studentId, boolean includeEnded, LocalDate today);
    Optional<AssignmentRecord> find(UUID id, boolean lock);
    List<CandidateView> findCandidates(UUID studentId, LocalDate from, LocalDate to);
    boolean slotAssignable(UUID slotId, LocalDate from, LocalDate to);
    List<ConflictView> findConflicts(UUID studentId, UUID slotId, LocalDate from, LocalDate to, UUID excludedId);
    UUID insert(UUID studentId, CreateCommand command, UUID actorId);
    int update(UUID id, UpdateCommand command, String reason, UUID actorId);
    int deleteFuture(UUID id, long version, LocalDate today);
    Claim claim(String scope, UUID key, String requestHash);
    void complete(String scope, UUID key, UUID resourceId, int status);
}
