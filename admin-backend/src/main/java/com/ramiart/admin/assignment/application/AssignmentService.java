package com.ramiart.admin.assignment.application;

import static com.ramiart.admin.assignment.application.AssignmentModels.*;
import com.ramiart.admin.assignment.application.AssignmentRepository.AssignmentRecord;
import com.ramiart.admin.assignment.application.AssignmentRepository.StudentRecord;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentService {
    private final AssignmentRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public AssignmentService(AssignmentRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssignmentPage list(UUID studentId, boolean includeEnded) {
        LocalDate today = LocalDate.now(clock);
        StudentRecord student = student(studentId, false);
        List<AssignmentView> all = repository.findByStudent(studentId, includeEnded, today).stream()
                .map(value -> view(value, today)).toList();
        return new AssignmentPage(new StudentSummary(student.id(), student.name(), student.status()),
                new AssignmentGroups(filter(all, "ACTIVE"), filter(all, "SCHEDULED"), filter(all, "ENDED")));
    }

    @Transactional(readOnly = true)
    public List<CandidateView> candidates(UUID studentId, LocalDate from, LocalDate to) {
        student(studentId, false);
        LocalDate start = from == null ? LocalDate.now(clock) : from;
        LocalDate end = to == null ? start.plusYears(1) : to;
        validatePeriod(start, end);
        return repository.findCandidates(studentId, start, end);
    }

    @Transactional
    public AssignmentView create(UUID studentId, CreateCommand command, UUID actorId, UUID key,
            RequestMetadata metadata) {
        validate(command);
        StudentRecord student = student(studentId, true);
        if (!"ACTIVE".equals(student.status())) throw new AssignmentException("STUDENT_STATUS_NOT_ASSIGNABLE");
        String scope = actorId + ":POST:/admin/students/{studentId}/schedule-assignments";
        String requestHash = hash(studentId + ":" + command.scheduleSlotId() + ":" + command.effectiveFrom()
                + ":" + command.effectiveTo());
        AssignmentRepository.Claim claim = repository.claim(scope, key, requestHash);
        if (!claim.claimed()) return view(repository.find(claim.resourceId(), false)
                .orElseThrow(() -> new AssignmentException("ASSIGNMENT_NOT_FOUND")), LocalDate.now(clock));
        if (!repository.slotAssignable(command.scheduleSlotId(), command.effectiveFrom(), command.effectiveTo())) {
            throw new AssignmentException("SCHEDULE_SLOT_NOT_FOUND");
        }
        List<ConflictView> conflicts = repository.findConflicts(studentId, command.scheduleSlotId(),
                command.effectiveFrom(), command.effectiveTo(), null);
        if (!conflicts.isEmpty()) throw new AssignmentException("STUDENT_SCHEDULE_CONFLICT", conflicts);
        UUID id = repository.insert(studentId, command, actorId);
        event(actorId, metadata, "STUDENT_SCHEDULE_ASSIGNED", id, Map.of("studentId", studentId));
        repository.complete(scope, key, id, 201);
        return view(repository.find(id, false).orElseThrow(), LocalDate.now(clock));
    }

    @Transactional
    public AssignmentView update(UUID id, UpdateCommand command, UUID actorId, RequestMetadata metadata) {
        if (command == null || command.version() < 0) throw new AssignmentException("VALIDATION_ERROR");
        validatePeriod(command.effectiveFrom(), command.effectiveTo());
        AssignmentRecord current = repository.find(id, true)
                .orElseThrow(() -> new AssignmentException("ASSIGNMENT_NOT_FOUND"));
        if (current.firstSnapshot() != null && command.effectiveFrom().isAfter(current.firstSnapshot())) {
            throw new AssignmentException("ASSIGNMENT_HISTORY_EXISTS");
        }
        if (current.lastSnapshot() != null && command.effectiveTo() != null
                && command.effectiveTo().isBefore(current.lastSnapshot())) {
            throw new AssignmentException("ASSIGNMENT_HISTORY_EXISTS");
        }
        boolean shortened = command.effectiveTo() != null
                && (current.effectiveTo() == null || command.effectiveTo().isBefore(current.effectiveTo()));
        String reason = normalizeReason(command.endedReason());
        if (shortened && reason == null) throw new AssignmentException("VALIDATION_ERROR");
        List<ConflictView> conflicts = repository.findConflicts(current.studentId(), current.scheduleSlotId(),
                command.effectiveFrom(), command.effectiveTo(), id);
        if (!conflicts.isEmpty()) throw new AssignmentException("STUDENT_SCHEDULE_CONFLICT", conflicts);
        UpdateCommand normalized = new UpdateCommand(command.effectiveFrom(), command.effectiveTo(), reason,
                command.version());
        if (repository.update(id, normalized, reason, actorId) != 1) {
            throw new AssignmentException("ASSIGNMENT_VERSION_CONFLICT");
        }
        event(actorId, metadata, "STUDENT_SCHEDULE_PERIOD_CHANGED", id,
                Map.of("previousVersion", command.version()));
        return view(repository.find(id, false).orElseThrow(), LocalDate.now(clock));
    }

    @Transactional
    public void deleteFuture(UUID id, long version, UUID actorId, RequestMetadata metadata) {
        if (version < 0) throw new AssignmentException("VALIDATION_ERROR");
        AssignmentRecord current = repository.find(id, true)
                .orElseThrow(() -> new AssignmentException("ASSIGNMENT_NOT_FOUND"));
        if (current.firstSnapshot() != null || !current.effectiveFrom().isAfter(LocalDate.now(clock))) {
            throw new AssignmentException("ASSIGNMENT_HISTORY_EXISTS");
        }
        if (repository.deleteFuture(id, version, LocalDate.now(clock)) != 1) {
            throw new AssignmentException("ASSIGNMENT_VERSION_CONFLICT");
        }
        event(actorId, metadata, "STUDENT_SCHEDULE_FUTURE_DELETED", id, Map.of("version", version));
    }

    private AssignmentView view(AssignmentRecord value, LocalDate today) {
        String status = value.effectiveFrom().isAfter(today) ? "SCHEDULED"
                : value.effectiveTo() != null && value.effectiveTo().isBefore(today) ? "ENDED" : "ACTIVE";
        AttendanceImpact impact = new AttendanceImpact(value.firstSnapshot(), value.lastSnapshot(),
                value.firstSnapshot() == null ? value.effectiveFrom() : value.firstSnapshot(),
                value.lastSnapshot() == null ? value.effectiveTo() : value.lastSnapshot());
        List<String> actions = "SCHEDULED".equals(status) && value.firstSnapshot() == null
                ? List.of("EDIT", "DELETE_FUTURE") : List.of("EDIT");
        return new AssignmentView(value.id(), value.studentId(), value.scheduleSlotId(), value.schedule(),
                value.effectiveFrom(), value.effectiveTo(), status, value.endedReason(), value.version(), impact, actions);
    }

    private StudentRecord student(UUID id, boolean lock) {
        return repository.findStudent(id, lock).orElseThrow(() -> new AssignmentException("STUDENT_NOT_FOUND"));
    }
    private static List<AssignmentView> filter(List<AssignmentView> all, String status) {
        return all.stream().filter(value -> status.equals(value.status())).toList();
    }
    private static void validate(CreateCommand command) {
        if (command == null || command.scheduleSlotId() == null) throw new AssignmentException("VALIDATION_ERROR");
        validatePeriod(command.effectiveFrom(), command.effectiveTo());
    }
    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || (to != null && to.isBefore(from))) throw new AssignmentException("VALIDATION_ERROR");
    }
    private static String normalizeReason(String value) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > 200) throw new AssignmentException("VALIDATION_ERROR");
        return result;
    }
    private void event(UUID actor, RequestMetadata metadata, String action, UUID target, Map<String,Object> details) {
        audit.record(new Event(clock.instant(), metadata.requestId(), "MGT-STUDENT-SCHEDULE-ASSIGN", "OPERATION",
                "ADMIN", actor, null, action, "STUDENT_SCHEDULE_ASSIGNMENT", target, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), details));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
