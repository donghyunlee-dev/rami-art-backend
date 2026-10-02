package com.ramiart.admin.course.application;

import com.ramiart.admin.course.application.CourseModels.ClassGroupView;
import com.ramiart.admin.course.application.CourseModels.ClassGroupWrite;
import com.ramiart.admin.course.application.CourseModels.CourseDetail;
import com.ramiart.admin.course.application.CourseModels.CoursePage;
import com.ramiart.admin.course.application.CourseModels.CourseWrite;
import com.ramiart.admin.course.application.CourseModels.OccupancySummary;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CourseRepository {

    record IdempotencyClaim(boolean claimed, UUID resourceId) {
    }

    record ClassGroupDeletionState(String status, long version, boolean referenced) {
    }

    CoursePage findCourses(String keyword, Boolean active, int page, int size);

    Optional<CourseDetail> findCourse(UUID courseId);

    Optional<ClassGroupView> findClassGroup(UUID classGroupId);

    Optional<OccupancySummary> findOccupancy(UUID classGroupId, LocalDate from, LocalDate to);

    void insertCourse(UUID id, CourseWrite command, UUID actorId);

    int updateCourse(UUID id, CourseWrite command, UUID actorId);

    void insertClassGroup(UUID id, UUID courseId, ClassGroupWrite command, UUID actorId);

    int updateClassGroup(UUID id, ClassGroupWrite command, UUID actorId);

    Optional<ClassGroupDeletionState> lockClassGroupForDeletion(UUID classGroupId);

    int deleteDraftClassGroup(UUID classGroupId, long version);

    int findMaximumFutureOccupancy(UUID classGroupId);

    boolean courseCodeExists(String code, UUID excludedId);

    boolean classGroupCodeExists(String code, UUID excludedId);

    boolean hasActiveClassGroups(UUID courseId);

    IdempotencyClaim claim(String scope, UUID key, String requestHash);

    void complete(String scope, UUID key, UUID resourceId, int responseStatus);
}
