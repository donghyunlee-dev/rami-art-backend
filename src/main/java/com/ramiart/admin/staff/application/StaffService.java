package com.ramiart.admin.staff.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.staff.application.StaffModels.AssignmentSetWrite;
import com.ramiart.admin.staff.application.StaffModels.AssignmentView;
import com.ramiart.admin.staff.application.StaffModels.AssignmentWrite;
import com.ramiart.admin.staff.application.StaffModels.ClassGroupOption;
import com.ramiart.admin.staff.application.StaffModels.StaffDetail;
import com.ramiart.admin.staff.application.StaffModels.StaffOptions;
import com.ramiart.admin.staff.application.StaffModels.StaffPage;
import com.ramiart.admin.staff.application.StaffModels.StaffWrite;
import com.ramiart.admin.staff.application.StaffPhoneProtector.ProtectedPhone;
import com.ramiart.admin.staff.application.StaffRepository.StaffRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffService {

    private final StaffRepository repository;
    private final StaffPhoneProtector phoneProtector;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public StaffService(StaffRepository repository, StaffPhoneProtector phoneProtector,
            AuditRecorder auditRecorder, Clock clock) {
        this.repository = repository;
        this.phoneProtector = phoneProtector;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffPage findStaff(String keyword, String status, String jobTitle, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new StaffException("STAFF_QUERY_INVALID");
        String normalizedStatus = upperToNull(status);
        String normalizedJobTitle = upperToNull(jobTitle);
        if (normalizedStatus != null && !normalizedStatus.matches("ACTIVE|INACTIVE")) {
            throw new StaffException("STAFF_QUERY_INVALID");
        }
        if (normalizedJobTitle != null && !normalizedJobTitle.matches("DIRECTOR|TEACHER|ASSISTANT|ADMIN")) {
            throw new StaffException("STAFF_QUERY_INVALID");
        }
        return repository.findStaff(trimToNull(keyword), normalizedStatus, normalizedJobTitle, page, size);
    }

    @Transactional(readOnly = true)
    public StaffDetail findStaff(UUID staffId) {
        StaffRecord record = repository.findStaff(staffId)
                .orElseThrow(() -> new StaffException("STAFF_NOT_FOUND"));
        return toDetail(record, repository.findAssignments(staffId));
    }

    @Transactional(readOnly = true)
    public StaffOptions findOptions() {
        return new StaffOptions(repository.findClassGroupOptions(), repository.findAdminUserOptions());
    }

    @Transactional
    public StaffDetail createStaff(StaffWrite rawCommand, UUID actorId, UUID idempotencyKey,
            RequestMetadata metadata) {
        StaffWrite command = normalize(rawCommand);
        validate(command, false);
        ProtectedPhone phone = phoneProtector.protect(command.phone());
        String scope = "STAFF_CREATE";
        var claim = repository.claim(scope, idempotencyKey, hash(command));
        if (!claim.claimed()) return findStaff(claim.resourceId());
        checkUniqueness(command, phone.hash(), null);
        UUID id = UUID.randomUUID();
        repository.insertStaff(id, command, phone.ciphertext(), phone.hash(), phone.last4(), actorId);
        audit(actorId, metadata, "STAFF_CREATED", id, Map.of(
                "staffCode", command.staffCode(), "jobTitle", command.jobTitle(), "status", command.status()));
        repository.complete(scope, idempotencyKey, id, 201);
        return findStaff(id);
    }

    @Transactional
    public StaffDetail updateStaff(UUID staffId, StaffWrite rawCommand, UUID actorId, UUID idempotencyKey,
            RequestMetadata metadata) {
        StaffWrite command = normalize(rawCommand);
        validate(command, true);
        String scope = "STAFF_UPDATE:" + staffId;
        var claim = repository.claim(scope, idempotencyKey, hash(command));
        if (!claim.claimed()) return findStaff(claim.resourceId());
        StaffRecord current = repository.findStaff(staffId)
                .orElseThrow(() -> new StaffException("STAFF_NOT_FOUND"));
        if (!current.staffCode().equals(command.staffCode())) throw new StaffException("STAFF_CODE_IMMUTABLE");
        ProtectedPhone phone = phoneProtector.protect(command.phone());
        checkUniqueness(command, phone.hash(), staffId);

        int affectedAssignments = 0;
        if ("INACTIVE".equals(command.status()) && repository.hasAssignmentAfter(staffId, command.leftOn())) {
            if (!Boolean.TRUE.equals(command.closeFutureAssignments())) {
                throw new StaffException("STAFF_FUTURE_ASSIGNMENT_EXISTS");
            }
            affectedAssignments = repository.closeAssignmentsAfter(staffId, command.leftOn(), actorId);
        }
        if (repository.updateStaff(staffId, command, phone.ciphertext(), phone.hash(), phone.last4(), actorId) == 0) {
            throw new StaffException("STAFF_VERSION_CONFLICT");
        }
        Map<String, Object> details = new HashMap<>();
        details.put("staffCode", command.staffCode());
        details.put("status", command.status());
        details.put("previousVersion", command.version());
        details.put("affectedAssignments", affectedAssignments);
        audit(actorId, metadata, "STAFF_UPDATED", staffId, details);
        repository.complete(scope, idempotencyKey, staffId, 200);
        return findStaff(staffId);
    }

    @Transactional
    public StaffDetail replaceAssignments(UUID staffId, AssignmentSetWrite rawCommand, UUID actorId,
            UUID idempotencyKey, RequestMetadata metadata) {
        if (rawCommand == null || rawCommand.staffVersion() < 0 || rawCommand.assignments() == null
                || rawCommand.assignments().size() > 100) {
            throw new StaffException("STAFF_VALIDATION_ERROR");
        }
        List<AssignmentWrite> assignments = rawCommand.assignments().stream().map(StaffService::normalize).toList();
        StaffRecord staff = repository.findStaff(staffId)
                .orElseThrow(() -> new StaffException("STAFF_NOT_FOUND"));
        if (!"ACTIVE".equals(staff.status())) throw new StaffException("STAFF_INACTIVE");
        validateAssignments(staff, assignments);
        String scope = "STAFF_ASSIGNMENTS_UPDATE:" + staffId;
        var claim = repository.claim(scope, idempotencyKey, hash(new AssignmentSetWrite(
                rawCommand.staffVersion(), assignments)));
        if (!claim.claimed()) return findStaff(claim.resourceId());
        if (repository.replaceAssignments(staffId, rawCommand.staffVersion(), assignments, actorId) == 0) {
            throw new StaffException("STAFF_VERSION_CONFLICT");
        }
        audit(actorId, metadata, "STAFF_ASSIGNMENTS_UPDATED", staffId,
                Map.of("assignmentCount", assignments.size(), "previousVersion", rawCommand.staffVersion()));
        repository.complete(scope, idempotencyKey, staffId, 200);
        return findStaff(staffId);
    }

    private void validateAssignments(StaffRecord staff, List<AssignmentWrite> assignments) {
        Map<UUID, ClassGroupOption> groups = repository.findClassGroupOptions().stream()
                .collect(Collectors.toMap(ClassGroupOption::id, value -> value));
        List<AssignmentView> current = repository.findAssignments(staff.id());
        HashSet<UUID> requestedIds = assignments.stream().map(AssignmentWrite::id)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        boolean removesHistory = current.stream()
                .anyMatch(value -> "ENDED".equals(value.derivedStatus()) && !requestedIds.contains(value.id()));
        if (removesHistory) throw new StaffException("STAFF_ASSIGNMENT_HISTORY_IMMUTABLE");

        HashSet<UUID> uniqueIds = new HashSet<>();
        for (AssignmentWrite assignment : assignments) {
            if (assignment.id() != null && !uniqueIds.add(assignment.id())) {
                throw new StaffException("STAFF_VALIDATION_ERROR");
            }
            ClassGroupOption group = groups.get(assignment.classGroupId());
            if (group == null || assignment.effectiveFrom() == null
                    || assignment.role() == null || !assignment.role().matches("LEAD|ASSISTANT")
                    || (assignment.effectiveTo() != null && assignment.effectiveTo().isBefore(assignment.effectiveFrom()))
                    || assignment.effectiveFrom().isBefore(staff.hiredOn())
                    || (staff.leftOn() != null && end(assignment).isAfter(staff.leftOn()))
                    || assignment.effectiveFrom().isBefore(group.startsOn())
                    || (group.endsOn() != null && end(assignment).isAfter(group.endsOn()))) {
                throw new StaffException("STAFF_PERIOD_OUTSIDE_EMPLOYMENT");
            }
            boolean isExisting = assignment.id() != null && current.stream()
                    .anyMatch(value -> value.id().equals(assignment.id()));
            if ("CLOSED".equals(group.status()) && !isExisting) {
                throw new StaffException("STAFF_PERIOD_OUTSIDE_EMPLOYMENT");
            }
            if (assignment.id() != null && assignment.version() == null) {
                throw new StaffException("STAFF_VALIDATION_ERROR");
            }
        }
    }

    private void checkUniqueness(StaffWrite command, String phoneHash, UUID excludedId) {
        if (repository.staffCodeExists(command.staffCode(), excludedId)) {
            throw new StaffException("STAFF_CODE_DUPLICATED");
        }
        if (repository.phoneHashExists(phoneHash, excludedId)) {
            throw new StaffException("STAFF_PHONE_DUPLICATED");
        }
        if (repository.adminUserLinked(command.adminUserId(), excludedId)) {
            throw new StaffException("STAFF_ADMIN_ALREADY_LINKED");
        }
    }

    private StaffDetail toDetail(StaffRecord value, List<AssignmentView> assignments) {
        return new StaffDetail(value.id(), value.staffCode(), value.name(), value.displayName(), value.jobTitle(),
                phoneProtector.reveal(value.phoneCiphertext()), value.hiredOn(), value.leftOn(), value.status(),
                value.adminUserId(), value.adminUserDisplayName(), value.version(), assignments);
    }

    private void audit(UUID actorId, RequestMetadata metadata, String action, UUID targetId,
            Map<String, Object> details) {
        auditRecorder.record(new Event(clock.instant(), metadata.requestId(), "MGT-STAFF-MANAGE", "OPERATION",
                "ADMIN", actorId, null, action, "STAFF", targetId, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), details));
    }

    private static StaffWrite normalize(StaffWrite value) {
        if (value == null) throw new StaffException("STAFF_VALIDATION_ERROR");
        return new StaffWrite(upper(value.staffCode()), trim(value.name()), trim(value.displayName()),
                upper(value.jobTitle()), normalizePhone(value.phone()), value.hiredOn(), value.leftOn(),
                upper(value.status()), value.adminUserId(), value.version(), value.closeFutureAssignments());
    }

    private static AssignmentWrite normalize(AssignmentWrite value) {
        if (value == null) throw new StaffException("STAFF_VALIDATION_ERROR");
        return new AssignmentWrite(value.id(), value.classGroupId(), upper(value.role()),
                value.effectiveFrom(), value.effectiveTo(), value.version());
    }

    private static void validate(StaffWrite value, boolean update) {
        if (value.staffCode() == null || !value.staffCode().matches("^[A-Z][A-Z0-9_]{1,29}$")
                || value.name() == null || value.name().isBlank() || value.name().length() > 100
                || value.displayName() == null || value.displayName().isBlank() || value.displayName().length() > 100
                || value.jobTitle() == null || !value.jobTitle().matches("DIRECTOR|TEACHER|ASSISTANT|ADMIN")
                || value.phone() == null || value.hiredOn() == null
                || value.status() == null || !value.status().matches("ACTIVE|INACTIVE")
                || ("ACTIVE".equals(value.status()) && value.leftOn() != null)
                || ("INACTIVE".equals(value.status()) && value.leftOn() == null)
                || (value.leftOn() != null && value.leftOn().isBefore(value.hiredOn()))
                || (update && value.version() == null)) {
            throw new StaffException("STAFF_VALIDATION_ERROR");
        }
    }

    private static String normalizePhone(String value) {
        if (value == null) return null;
        String compact = value.trim().replaceAll("[\\s().-]", "");
        if (compact.startsWith("0")) compact = "+82" + compact.substring(1);
        if (!compact.matches("^\\+[1-9][0-9]{7,14}$")) throw new StaffException("STAFF_VALIDATION_ERROR");
        return compact;
    }

    private static LocalDate end(AssignmentWrite value) {
        return value.effectiveTo() == null ? LocalDate.MAX : value.effectiveTo();
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

    private static String upperToNull(String value) {
        String upper = upper(value);
        return upper == null || upper.isEmpty() ? null : upper;
    }

    private static String hash(Object value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {
    }
}
