package com.ramiart.admin.staff.application;

import com.ramiart.admin.staff.application.StaffModels.AdminUserOption;
import com.ramiart.admin.staff.application.StaffModels.AssignmentView;
import com.ramiart.admin.staff.application.StaffModels.AssignmentWrite;
import com.ramiart.admin.staff.application.StaffModels.ClassGroupOption;
import com.ramiart.admin.staff.application.StaffModels.StaffPage;
import com.ramiart.admin.staff.application.StaffModels.StaffWrite;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRepository {

    record IdempotencyClaim(boolean claimed, UUID resourceId) {
    }

    record StaffRecord(
            UUID id, String staffCode, String name, String displayName, String jobTitle,
            byte[] phoneCiphertext, String phoneHash, String phoneLast4,
            LocalDate hiredOn, LocalDate leftOn, String status, UUID adminUserId,
            String adminUserDisplayName, long version) {
    }

    StaffPage findStaff(String keyword, String status, String jobTitle, int page, int size);

    Optional<StaffRecord> findStaff(UUID staffId);

    List<AssignmentView> findAssignments(UUID staffId);

    List<ClassGroupOption> findClassGroupOptions();

    List<AdminUserOption> findAdminUserOptions();

    void insertStaff(UUID id, StaffWrite command, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, UUID actorId);

    int updateStaff(UUID id, StaffWrite command, byte[] phoneCiphertext, String phoneHash,
            String phoneLast4, UUID actorId);

    boolean staffCodeExists(String code, UUID excludedId);

    boolean phoneHashExists(String phoneHash, UUID excludedId);

    boolean adminUserLinked(UUID adminUserId, UUID excludedId);

    boolean hasAssignmentAfter(UUID staffId, LocalDate date);

    int closeAssignmentsAfter(UUID staffId, LocalDate date, UUID actorId);

    int replaceAssignments(UUID staffId, long staffVersion, List<AssignmentWrite> assignments, UUID actorId);

    IdempotencyClaim claim(String scope, UUID key, String requestHash);

    void complete(String scope, UUID key, UUID resourceId, int responseStatus);
}
