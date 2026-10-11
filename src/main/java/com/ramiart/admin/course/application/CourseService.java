package com.ramiart.admin.course.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.course.application.CourseModels.ClassGroupView;
import com.ramiart.admin.course.application.CourseModels.ClassGroupWrite;
import com.ramiart.admin.course.application.CourseModels.CourseDetail;
import com.ramiart.admin.course.application.CourseModels.CoursePage;
import com.ramiart.admin.course.application.CourseModels.CourseWrite;
import com.ramiart.admin.course.application.CourseModels.OccupancySummary;
import java.time.LocalDate;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseRepository repository;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public CourseService(CourseRepository repository, AuditRecorder auditRecorder, Clock clock) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CoursePage findCourses(String keyword, Boolean active, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new CourseException("COURSE_QUERY_INVALID");
        return repository.findCourses(trimToNull(keyword), active, page, size);
    }

    @Transactional(readOnly = true)
    public CourseDetail findCourse(UUID courseId) {
        return repository.findCourse(courseId).orElseThrow(() -> new CourseException("COURSE_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public OccupancySummary findOccupancy(UUID classGroupId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(366))) {
            throw new CourseException("COURSE_QUERY_INVALID");
        }
        return repository.findOccupancy(classGroupId, from, to)
                .orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
    }

    @Transactional
    public CourseDetail createCourse(CourseWrite rawCommand, UUID actorId, UUID idempotencyKey,
            RequestMetadata metadata) {
        CourseWrite command = normalize(rawCommand);
        validateCourse(command, false);
        String scope = "COURSE_CREATE";
        var claim = repository.claim(scope, idempotencyKey, hash(command));
        if (!claim.claimed()) return findCourse(claim.resourceId());
        if (repository.courseCodeExists(command.code(), null)) throw new CourseException("COURSE_CODE_DUPLICATED");
        UUID id = UUID.randomUUID();
        repository.insertCourse(id, command, actorId);
        audit(actorId, metadata, "COURSE_CREATED", "COURSE", id, Map.of("code", command.code()));
        repository.complete(scope, idempotencyKey, id, 201);
        return findCourse(id);
    }

    @Transactional
    public CourseDetail updateCourse(UUID id, CourseWrite rawCommand, UUID actorId, UUID idempotencyKey,
            RequestMetadata metadata) {
        CourseWrite command = normalize(rawCommand);
        validateCourse(command, true);
        String scope = "COURSE_UPDATE:" + id;
        var claim = repository.claim(scope, idempotencyKey, hash(command));
        if (!claim.claimed()) return findCourse(claim.resourceId());
        CourseDetail current = findCourse(id);
        if (repository.courseCodeExists(command.code(), id)) throw new CourseException("COURSE_CODE_DUPLICATED");
        if (current.active() && !command.active() && repository.hasActiveClassGroups(id)) {
            throw new CourseException("COURSE_HAS_ACTIVE_GROUPS");
        }
        if (repository.updateCourse(id, command, actorId) == 0) throw new CourseException("COURSE_VERSION_CONFLICT");
        audit(actorId, metadata, "COURSE_UPDATED", "COURSE", id,
                Map.of("code", command.code(), "previousVersion", command.version()));
        repository.complete(scope, idempotencyKey, id, 200);
        return findCourse(id);
    }

    @Transactional
    public ClassGroupView createClassGroup(UUID courseId, ClassGroupWrite rawCommand, UUID actorId,
            UUID idempotencyKey,
            RequestMetadata metadata) {
        CourseDetail course = findCourse(courseId);
        if (!course.active()) throw new CourseException("COURSE_INACTIVE");
        ClassGroupWrite command = normalize(rawCommand);
        validateClassGroup(command, false);
        String scope = "CLASS_GROUP_CREATE:" + courseId;
        var claim = repository.claim(scope, idempotencyKey, hash(command));
        if (!claim.claimed()) return repository.findClassGroup(claim.resourceId())
                .orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
        if (repository.classGroupCodeExists(command.code(), null)) throw new CourseException("COURSE_CODE_DUPLICATED");
        UUID id = UUID.randomUUID();
        repository.insertClassGroup(id, courseId, command, actorId);
        audit(actorId, metadata, "CLASS_GROUP_CREATED", "CLASS_GROUP", id,
                Map.of("courseId", courseId, "code", command.code()));
        repository.complete(scope, idempotencyKey, id, 201);
        return repository.findClassGroup(id).orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
    }

    @Transactional
    public ClassGroupView updateClassGroup(UUID id, ClassGroupWrite rawCommand, UUID actorId,
            UUID idempotencyKey,
            RequestMetadata metadata) {
        ClassGroupWrite command = normalize(rawCommand);
        validateClassGroup(command, true);
        String scope = "CLASS_GROUP_UPDATE:" + id;
        var claim = repository.claim(scope, idempotencyKey, hash(command));
        if (!claim.claimed()) return repository.findClassGroup(claim.resourceId())
                .orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
        ClassGroupView current = repository.findClassGroup(id)
                .orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
        if (repository.classGroupCodeExists(command.code(), id)) throw new CourseException("COURSE_CODE_DUPLICATED");
        int maximumFutureOccupancy = repository.findMaximumFutureOccupancy(id);
        if (command.capacity() < maximumFutureOccupancy) {
            throw new CourseException("CLASS_CAPACITY_BELOW_OCCUPANCY");
        }
        if (repository.updateClassGroup(id, command, actorId) == 0) {
            throw new CourseException("COURSE_VERSION_CONFLICT");
        }
        audit(actorId, metadata, "CLASS_GROUP_UPDATED", "CLASS_GROUP", id,
                Map.of("code", command.code(), "previousVersion", command.version()));
        repository.complete(scope, idempotencyKey, id, 200);
        return repository.findClassGroup(id).orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
    }

    @Transactional
    public void deleteClassGroup(UUID id, long version, UUID actorId, UUID idempotencyKey,
            RequestMetadata metadata) {
        if (version < 0) throw new CourseException("COURSE_VALIDATION_ERROR");
        String scope = "CLASS_GROUP_DELETE:" + id;
        var claim = repository.claim(scope, idempotencyKey, hash(id + ":" + version));
        if (!claim.claimed()) return;

        var state = repository.lockClassGroupForDeletion(id)
                .orElseThrow(() -> new CourseException("CLASS_GROUP_NOT_FOUND"));
        if (!"DRAFT".equals(state.status())) throw new CourseException("CLASS_GROUP_NOT_DRAFT");
        if (state.referenced()) throw new CourseException("CLASS_GROUP_REFERENCED");
        if (state.version() != version || repository.deleteDraftClassGroup(id, version) == 0) {
            throw new CourseException("COURSE_VERSION_CONFLICT");
        }

        audit(actorId, metadata, "CLASS_GROUP_DELETED", "CLASS_GROUP", id,
                Map.of("status", state.status(), "version", version));
        repository.complete(scope, idempotencyKey, id, 204);
    }

    private void audit(UUID actorId, RequestMetadata metadata, String action, String targetType,
            UUID targetId, Map<String, Object> details) {
        auditRecorder.record(new Event(clock.instant(), metadata.requestId(), "MGT-COURSE-MASTER", "OPERATION",
                "ADMIN", actorId, null, action, targetType, targetId, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), details));
    }

    private static CourseWrite normalize(CourseWrite value) {
        return new CourseWrite(upper(value.code()), trim(value.name()), trimToNull(value.description()),
                trimToNull(value.ageGuide()), value.sessionDurationMinutes(), value.weeklySessions(),
                value.displayOrder(), value.active(), value.version());
    }

    private static ClassGroupWrite normalize(ClassGroupWrite value) {
        return new ClassGroupWrite(upper(value.code()), trim(value.name()), upper(value.roomCode()), value.capacity(),
                value.waitlistEnabled(), value.makeupValidDays(), value.startsOn(), value.endsOn(),
                upper(value.status()), value.version());
    }

    private static void validateCourse(CourseWrite value, boolean update) {
        if (value.code() == null || !value.code().matches("^[A-Z][A-Z0-9_]{1,29}$")
                || value.name() == null || value.name().isBlank() || value.name().length() > 100
                || value.displayOrder() < 0 || (value.description() != null && value.description().length() > 500)
                || (value.ageGuide() != null && value.ageGuide().length() > 100)
                || ((value.sessionDurationMinutes() == null) != (value.weeklySessions() == null))
                || (value.sessionDurationMinutes() != null && (value.sessionDurationMinutes() < 20
                        || value.sessionDurationMinutes() > 240 || value.sessionDurationMinutes() % 5 != 0))
                || (value.weeklySessions() != null && (value.weeklySessions() < 1 || value.weeklySessions() > 7))
                || (update && value.version() == null)) {
            throw new CourseException("COURSE_VALIDATION_ERROR");
        }
    }

    private static void validateClassGroup(ClassGroupWrite value, boolean update) {
        if (value.code() == null || !value.code().matches("^[A-Z][A-Z0-9_]{1,29}$")
                || value.name() == null || value.name().isBlank() || value.name().length() > 100
                || value.roomCode() == null || value.roomCode().isBlank() || value.roomCode().length() > 30
                || value.capacity() < 1 || value.capacity() > 100
                || value.makeupValidDays() < 0 || value.makeupValidDays() > 180
                || value.startsOn() == null || (value.endsOn() != null && value.endsOn().isBefore(value.startsOn()))
                || value.status() == null || !value.status().matches("DRAFT|ACTIVE|CLOSED")
                || (update && value.version() == null)) {
            throw new CourseException("COURSE_VALIDATION_ERROR");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String hash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {
    }
}
