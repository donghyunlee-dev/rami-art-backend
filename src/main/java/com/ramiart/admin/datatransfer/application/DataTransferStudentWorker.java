package com.ramiart.admin.datatransfer.application;

import com.ramiart.admin.student.application.StudentService;
import com.ramiart.admin.student.application.StudentModels.StudentCreate;
import com.ramiart.admin.student.application.StudentModels.GuardianWrite;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataTransferStudentWorker {
    private final StudentService students;
    public DataTransferStudentWorker(StudentService students) { this.students = students; }

    public UUID duplicateCandidate(Map<String, String> row) {
        String email = blankToNull(row.get("guardianEmail"));
        GuardianWrite guardian = new GuardianWrite(null, row.get("guardianName"), row.get("relationship"), null,
                row.get("guardianPhone"), email, email == null ? "SMS" : "EMAIL", true, 0);
        StudentCreate request = new StudentCreate(row.get("studentName"), date(row.get("birthday")), null,
                date(row.get("joinedAt")), false, List.of(guardian));
        return students.duplicateCandidates(request).stream().map(candidate -> candidate.id()).findFirst().orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID create(Map<String, String> row, UUID actor, UUID idempotencyKey,
            DataTransferService.RequestMetadata metadata, boolean duplicateConfirmed) {
        LocalDate birthday = date(row.get("birthday"));
        LocalDate joinedAt = date(row.get("joinedAt"));
        String email = blankToNull(row.get("guardianEmail"));
        GuardianWrite guardian = new GuardianWrite(null, row.get("guardianName"), row.get("relationship"), null,
                row.get("guardianPhone"), email, email == null ? "SMS" : "EMAIL", true, 0);
        StudentCreate request = new StudentCreate(row.get("studentName"), birthday, null, joinedAt,
                duplicateConfirmed, List.of(guardian));
        return students.create(request, actor, idempotencyKey,
                new StudentService.RequestMetadata(metadata.requestId(), metadata.ipAddress(), metadata.userAgent())).id();
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
