package com.ramiart.admin.attendance.application;

import static com.ramiart.admin.attendance.application.AttendanceModels.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public final class AttendanceService {
    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private final AttendanceRepository repository;
    public AttendanceService(AttendanceRepository repository) { this.repository = repository; }

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

    private Session toSession(List<AttendanceRepository.StudentRow> rows, boolean pendingOnly, boolean canWrite, boolean canClose) {
        var first = rows.getFirst();
        var targets = rows.stream().filter(row -> row.studentId() != null).toList();
        int present = 0, late = 0, absent = 0, excused = 0, incomplete = 0;
        for (var row : targets) switch (row.attendanceStatus()) {
            case "PRESENT" -> present++; case "LATE" -> late++; case "ABSENT" -> absent++; case "EXCUSED" -> excused++; default -> incomplete++;
        }
        if (pendingOnly && (incomplete == 0 || "CANCELLED".equals(first.sessionStatus()))) return null;
        List<Student> students = targets.stream().filter(row -> !pendingOnly || row.attendanceStatus() == null).map(row -> new Student(
                row.studentId(), row.studentName(), row.displayOrder(), row.attendanceStatus() == null ? null : new Attendance(
                        row.attendanceStatus(), row.checkInTime(), row.reason(), row.attendanceVersion(), row.updatedAt()),
                "OPEN".equals(row.sessionStatus()) && canWrite ? List.of("EDIT_ATTENDANCE") : List.of())).toList();
        List<String> actions = new ArrayList<>();
        if ("OPEN".equals(first.sessionStatus()) && canWrite) actions.add("EDIT_ATTENDANCE");
        if ("OPEN".equals(first.sessionStatus()) && canClose && rows.size() > 0) actions.add("CLOSE_ATTENDANCE");
        return new Session(first.sessionId(), first.scheduleSlotId(), first.date(), first.className(), first.startsAt(), first.endsAt(), first.sessionStatus(), first.sessionVersion(),
                new Summary(targets.size(), present, late, absent, excused, incomplete), students, actions);
    }
    private static boolean has(Authentication authentication, String authority) { return authentication != null && authentication.getAuthorities().stream().anyMatch(value -> authority.equals(value.getAuthority())); }
    public static final class AttendanceException extends RuntimeException { private final String code; public AttendanceException(String code) { this.code = code; } public String code() { return code; } }
}
