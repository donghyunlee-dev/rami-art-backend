package com.ramiart.admin.course.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CourseModels {

    private CourseModels() {
    }

    public record CoursePage(List<CourseSummary> content, long totalElements, int page, int size) {
    }

    public record CourseSummary(
            UUID id, String code, String name, String description, String ageGuide,
            int displayOrder, boolean active, long version, int classGroupCount, int activeClassGroupCount) {
    }

    public record CourseDetail(
            UUID id, String code, String name, String description, String ageGuide,
            int displayOrder, boolean active, long version, List<ClassGroupView> classGroups) {
    }

    public record ClassGroupView(
            UUID id, UUID courseId, String code, String name, String roomCode, int capacity,
            boolean waitlistEnabled, int makeupValidDays, LocalDate startsOn, LocalDate endsOn,
            String status, long version, int regularOccupancy, int reservedMakeupCount,
            int availableSeats, int futureSlotCount, int futureAssignmentCount,
            String currentLeadStaff, List<String> actions) {
    }

    public record CourseWrite(
            String code, String name, String description, String ageGuide,
            int displayOrder, boolean active, Long version) {
    }

    public record ClassGroupWrite(
            String code, String name, String roomCode, int capacity, boolean waitlistEnabled,
            int makeupValidDays, LocalDate startsOn, LocalDate endsOn, String status, Long version) {
    }

    public record OccupancySummary(
            UUID classGroupId, LocalDate from, LocalDate to, int regularOccupancy,
            int reservedMakeupCount, int availableSeats) {
    }
}
