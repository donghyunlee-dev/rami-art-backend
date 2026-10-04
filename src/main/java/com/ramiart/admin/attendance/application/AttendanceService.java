package com.ramiart.admin.attendance.application;

import static com.ramiart.admin.attendance.application.AttendanceModels.*;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.attendance.application.AttendanceRepository.ClosureState;
import com.ramiart.admin.attendance.application.AttendanceRepository.AttendanceValues;
import com.ramiart.admin.attendance.application.AttendanceRepository.SessionState;
import com.ramiart.admin.attendance.application.AttendanceRepository.TargetState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {
    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private final AttendanceRepository repository;
    private final AuditRecorder audit;
    public AttendanceService(AttendanceRepository repository, AuditRecorder audit) { this.repository = repository; this.audit = audit; }

    public Day find(LocalDate date, String filter, Authentication authentication) {
        LocalDate today = LocalDate.now(STUDIO_ZONE);
        if (date.isBefore(today.minusDays(31)) || date.isAfter(today.plusDays(31)))
            throw new AttendanceException("ATTENDANCE_DATE_OUT_OF_RANGE");
        boolean pendingOnly = "PENDING".equalsIgnoreCase(filter);
        boolean canWrite = has(authentication, "ATTENDANCE_WRITE");
        boolean canClose = has(authentication, "ATTENDANCE_CLOSE");
        Map<UUID, List<AttendanceRepository.StudentRow>> grouped = new LinkedHashMap<>();
        for (var row : repository.findByDate(date)) grouped.computeIfAbsent(row.sessionId(), ignored -> new ArrayList<>()).add(row);
        List<Session> sessions = grouped.values().stream().map(rows -> toSession(rows, pendingOnly, canWrite, canClose)).filter(Objects::nonNull).toList();
        return new Day(date, STUDIO_ZONE.getId(), sessions);
    }

    public Session findById(UUID sessionId, Authentication authentication) {
        if (!has(authentication, "ATTENDANCE_READ")) throw new AttendanceException("ATTENDANCE_READ_DENIED");
        List<AttendanceRepository.StudentRow> rows = repository.findBySessionId(sessionId)
                .orElseThrow(() -> new AttendanceException("ATTENDANCE_SESSION_NOT_FOUND"));
        return toSession(rows, false, has(authentication, "ATTENDANCE_WRITE"), has(authentication, "ATTENDANCE_CLOSE"));
    }

    private Session toSession(List<AttendanceRepository.StudentRow> rows, boolean pendingOnly, boolean canWrite, boolean canClose) {
        var first = rows.getFirst();
        var targets = rows.stream().filter(row -> row.studentId() != null).toList();
        int present = 0, late = 0, absent = 0, excused = 0, incomplete = 0;
        for (var row : targets) {
            if (row.attendanceStatus() == null) {
                incomplete++;
                continue;
            }
            switch (row.attendanceStatus()) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "ABSENT" -> absent++;
                case "EXCUSED" -> excused++;
                default -> incomplete++;
            }
        }
        if (pendingOnly && (incomplete == 0 || "CANCELLED".equals(first.sessionStatus()))) return null;
        List<Student> students = targets.stream().filter(row -> !pendingOnly || row.attendanceStatus() == null).map(row -> new Student(
                row.studentId(), row.studentName(), row.displayOrder(), row.attendanceStatus() == null ? null : new Attendance(
                        row.attendanceStatus(), row.checkInTime(), row.reason(), row.makeupEligible(), row.attendanceVersion(), row.updatedAt()),
                row.makeupCaseId(), row.makeupStatus(),
                "OPEN".equals(row.sessionStatus()) && canWrite ? List.of("EDIT_ATTENDANCE") : List.of())).toList();
        List<String> actions = new ArrayList<>();
        if ("OPEN".equals(first.sessionStatus()) && canWrite) actions.add("EDIT_ATTENDANCE");
        if ("OPEN".equals(first.sessionStatus()) && canClose && !targets.isEmpty()) actions.add("CLOSE_ATTENDANCE");
        Summary calculated = new Summary(targets.size(), present, late, absent, excused, incomplete);
        Closure closure = null;
        if ("CLOSED".equals(first.sessionStatus())) {
            Summary snapshot = new Summary(targets.size(), Objects.requireNonNullElse(first.presentCount(), present),
                    Objects.requireNonNullElse(first.lateCount(), late), Objects.requireNonNullElse(first.absentCount(), absent),
                    Objects.requireNonNullElse(first.excusedCount(), excused), 0);
            closure = new Closure(first.closedBy(), first.closedByName(), first.closedAt(), snapshot);
            calculated = snapshot;
        }
        return new Session(first.sessionId(), first.scheduleSlotId(), first.classGroupId(), first.roomCode(), first.makeupValidDays(),
                first.date(), first.className(), first.startsAt(), first.endsAt(), first.sessionStatus(), first.sessionVersion(),
                calculated, students, closure, actions);
    }

    @Transactional
    public SavedAttendance save(UUID sessionId, UUID studentId, AttendanceWrite command, Authentication authentication, RequestMetadata metadata) {
        UUID actorId = actor(authentication, "ATTENDANCE_WRITE");
        validate(command);
        SessionState session = repository.lockSession(sessionId).orElseThrow(() -> new AttendanceException("ATTENDANCE_SESSION_NOT_FOUND"));
        ensureOpen(session.status());
        if (session.version() != command.sessionVersion()) throw new AttendanceException("ATTENDANCE_VERSION_CONFLICT");
        validateShape(command, session);
        TargetState target = repository.lockTarget(sessionId, studentId).orElseThrow(() -> new AttendanceException("ATTENDANCE_TARGET_NOT_FOUND"));
        if ((target.attendanceId() == null && command.version() != null)
                || (target.attendanceId() != null && !Objects.equals(target.attendanceVersion(), command.version())))
            throw new AttendanceException("ATTENDANCE_VERSION_CONFLICT");
        int changed = target.attendanceId() == null
                ? repository.insertAttendance(sessionId, studentId, actorId, command)
                : repository.updateAttendance(sessionId, studentId, actorId, command);
        if (changed != 1 || repository.incrementSessionVersion(sessionId, command.sessionVersion()) != 1)
            throw new AttendanceException("ATTENDANCE_VERSION_CONFLICT");
        event(actorId, metadata, "ATTENDANCE_RESULT_SAVED", sessionId, Map.of("status", command.status()));
        AttendanceValues saved = repository.findAttendance(sessionId, studentId).orElseThrow(() -> new AttendanceException("ATTENDANCE_SAVE_FAILED"));
        Summary summary = summary(repository.findTargets(sessionId));
        return new SavedAttendance(new Attendance(saved.status(), saved.checkInTime(), saved.reason(), saved.makeupEligible(),
                saved.version(), saved.updatedAt()), session.version() + 1, summary);
    }

    @Transactional
    public CloseResult close(UUID sessionId, CloseWrite command, Authentication authentication, UUID key, RequestMetadata metadata) {
        UUID actorId = actor(authentication, "ATTENDANCE_CLOSE");
        if (command == null || command.version() < 0 || key == null) throw new AttendanceException("VALIDATION_ERROR");
        String scope = actorId + ":POST:/admin/attendance-sessions/{sessionId}/closures";
        String requestHash = hash(sessionId + ":" + command.version());
        if (!repository.claimIdempotency(scope, key, requestHash)) {
            if (!repository.idempotencyHash(scope, key).filter(requestHash::equals).isPresent())
                throw new AttendanceException("IDEMPOTENCY_KEY_REUSED");
            SessionState existing = repository.lockSession(sessionId).orElseThrow(() -> new AttendanceException("ATTENDANCE_SESSION_NOT_FOUND"));
            if ("CLOSED".equals(existing.status())) return new CloseResult(closedSnapshot(sessionId), false);
            throw new AttendanceException("ATTENDANCE_VERSION_CONFLICT");
        }
        SessionState session = repository.lockSession(sessionId).orElseThrow(() -> new AttendanceException("ATTENDANCE_SESSION_NOT_FOUND"));
        if ("CANCELLED".equals(session.status())) throw new AttendanceException("ATTENDANCE_SESSION_CANCELLED");
        if ("CLOSED".equals(session.status())) {
            repository.completeIdempotency(scope, key, sessionId, 200);
            return new CloseResult(closedSnapshot(sessionId), false);
        }
        if (session.version() != command.version()) throw new AttendanceException("ATTENDANCE_VERSION_CONFLICT");
        List<TargetState> targets = repository.findTargets(sessionId);
        if (targets.isEmpty()) throw new AttendanceException("ATTENDANCE_EMPTY_SESSION");
        if (targets.stream().anyMatch(target -> target.attendanceId() == null)) throw new AttendanceException("ATTENDANCE_INCOMPLETE");
        Summary summary = summary(targets);
        OffsetDateTime now = java.time.OffsetDateTime.now(STUDIO_ZONE).truncatedTo(ChronoUnit.SECONDS);
        if (repository.closeSession(sessionId, command.version(), actorId, now, summary) != 1)
            throw new AttendanceException("ATTENDANCE_VERSION_CONFLICT");
        List<AttendanceRepository.MakeupValue> makeups = session.makeupValidDays() > 0
                ? repository.createMakeupCases(sessionId, actorId, session.makeupValidDays()) : List.of();
        event(actorId, metadata, "ATTENDANCE_SESSION_CLOSED", sessionId, Map.of("targetCount", summary.totalCount(), "createdMakeupCount", makeups.size()));
        repository.completeIdempotency(scope, key, sessionId, 201);
        ClosedSession closed = closedSnapshot(sessionId);
        return new CloseResult(new ClosedSession(closed.sessionId(), closed.status(), closed.version(), closed.summary(), closed.closedBy(),
                closed.closedByName(), closed.closedAt(), makeups.size(), makeups.stream().map(AttendanceRepository.MakeupValue::id).toList()), true);
    }

    private ClosedSession closedSnapshot(UUID sessionId) {
        SessionState session = repository.lockSession(sessionId).orElseThrow(() -> new AttendanceException("ATTENDANCE_SESSION_NOT_FOUND"));
        ClosureState closure = repository.findClosure(sessionId).orElseThrow(() -> new AttendanceException("ATTENDANCE_CLOSE_FAILED"));
        Summary summary = new Summary(closure.targetCount(), closure.presentCount(), closure.lateCount(), closure.absentCount(), closure.excusedCount(), 0);
        List<UUID> makeupIds = repository.findMakeupIdsForOriginSession(sessionId);
        return new ClosedSession(sessionId, "CLOSED", session.version(), summary, closure.closedBy(), closure.closedByName(),
                closure.closedAt(), makeupIds.size(), makeupIds);
    }

    private static Summary summary(List<TargetState> targets) {
        int present = 0, late = 0, absent = 0, excused = 0;
        for (TargetState target : targets) switch (target.status()) {
            case "PRESENT" -> present++;
            case "LATE" -> late++;
            case "ABSENT" -> absent++;
            case "EXCUSED" -> excused++;
            default -> throw new AttendanceException("ATTENDANCE_INCOMPLETE");
        }
        return new Summary(targets.size(), present, late, absent, excused, 0);
    }

    private static void validate(AttendanceWrite command) {
        if (command == null || command.status() == null || command.makeupEligible() == null || command.sessionVersion() < 0
                || (command.version() != null && command.version() < 0)) throw new AttendanceException("VALIDATION_ERROR");
        if (!Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED").contains(command.status())) throw new AttendanceException("VALIDATION_ERROR");
        if (command.status().equals("LATE") && command.checkInTime() == null) throw new AttendanceException("ATTENDANCE_DETAIL_REQUIRED");
        if (!command.status().equals("LATE") && command.checkInTime() != null) throw new AttendanceException("VALIDATION_ERROR");
        if (Set.of("ABSENT", "EXCUSED").contains(command.status())) {
            if (command.reason() == null || command.reason().trim().isEmpty() || command.reason().trim().length() > 200)
                throw new AttendanceException("ATTENDANCE_DETAIL_REQUIRED");
        } else if (command.reason() != null) throw new AttendanceException("VALIDATION_ERROR");
        if (command.makeupEligible() && !Set.of("ABSENT", "EXCUSED").contains(command.status()))
            throw new AttendanceException("ATTENDANCE_MAKEUP_NOT_ALLOWED");
    }

    private static void validateShape(AttendanceWrite command, SessionState session) {
        if (command.status().equals("LATE")) {
            LocalTime earliest = session.startsAt().atZoneSameInstant(STUDIO_ZONE).toLocalTime().minusHours(2);
            LocalTime latest = session.endsAt().atZoneSameInstant(STUDIO_ZONE).toLocalTime();
            if (command.checkInTime().isBefore(earliest) || command.checkInTime().isAfter(latest))
                throw new AttendanceException("ATTENDANCE_DETAIL_REQUIRED");
        }
        if (command.makeupEligible() && session.makeupValidDays() == 0) throw new AttendanceException("ATTENDANCE_MAKEUP_NOT_ALLOWED");
    }

    private static void ensureOpen(String status) {
        if ("CLOSED".equals(status)) throw new AttendanceException("ATTENDANCE_SESSION_CLOSED");
        if ("CANCELLED".equals(status)) throw new AttendanceException("ATTENDANCE_SESSION_CANCELLED");
    }

    private static UUID actor(Authentication authentication, String authority) {
        if (!has(authentication, authority)) throw new AttendanceException(authority + "_DENIED");
        try { return UUID.fromString(authentication.getName()); }
        catch (RuntimeException exception) { throw new AttendanceException("SESSION_REQUIRED"); }
    }

    private void event(UUID actorId, RequestMetadata metadata, String action, UUID sessionId, Map<String, Object> details) {
        audit.record(new Event(Instant.now(), metadata.requestId(), action.contains("CLOSED") ? "MGT-ATTENDANCE-CLOSE" : "MGT-ATTENDANCE-TODAY",
                "OPERATION", "ADMIN", actorId, repository.findAdminDisplayName(actorId), action, "ATTENDANCE_SESSION", sessionId,
                "SUCCESS", null, metadata.ipAddress(), metadata.userAgent(), details));
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
    private static boolean has(Authentication authentication, String authority) { return authentication != null && authentication.getAuthorities().stream().anyMatch(value -> authority.equals(value.getAuthority())); }
    public static final class AttendanceException extends RuntimeException { private final String code; public AttendanceException(String code) { this.code = code; } public String code() { return code; } }
}
