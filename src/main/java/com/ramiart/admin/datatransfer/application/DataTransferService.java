package com.ramiart.admin.datatransfer.application;

import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class DataTransferService {
    private static final Map<String, String> TEMPLATES = Map.of(
            "STUDENT", "studentName,birthday,joinedAt,guardianName,relationship,guardianPhone,guardianEmail,courseCode,classGroupCode\r\n",
            "PAYMENT", "billingId,studentId,yearMonth,paidOn,amount,method,memo\r\n",
            "ATTENDANCE", "attendanceSessionId,studentId,attendanceStatus\r\n");
    private final DataTransferRepository repository;
    private final ObjectMapper mapper;

    public DataTransferService(DataTransferRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public Template template(String domain, Authentication authentication) {
        require(authentication, "DATA_TRANSFER_IMPORT");
        String normalized = domain == null ? "" : domain.trim().toUpperCase(java.util.Locale.ROOT);
        String csv = TEMPLATES.get(normalized);
        if (csv == null) throw new DataTransferException("TRANSFER_DOMAIN_INVALID");
        return new Template(normalized + "_V1", csv);
    }

    public Job job(UUID id, Authentication authentication) {
        UUID actor = requireAny(authentication);
        JobRecord record = repository.findJob(id, actor)
                .orElseThrow(() -> new DataTransferException("TRANSFER_JOB_NOT_FOUND"));
        int processed = record.confirmedCount() + record.failedCount();
        int progress = record.totalCount() == 0 ? 0 : Math.min(100, processed * 100 / record.totalCount());
        boolean downloadable = "EXPORT".equals(record.direction())
                && List.of("COMPLETED", "PARTIAL").contains(record.status());
        return new Job(record.id(), record.direction(), record.domain(), record.status(), record.templateVersion(),
                record.totalCount(), record.validCount(), record.invalidCount(), record.duplicateCount(),
                record.confirmedCount(), record.failedCount(), progress, record.version(), record.expiresAt(), downloadable);
    }

    public RowPage rows(UUID id, List<String> requestedStatuses, String cursor, int size, Authentication authentication) {
        require(authentication, "DATA_TRANSFER_IMPORT");
        if (size < 1 || size > 100) throw new DataTransferException("VALIDATION_ERROR");
        Job job = job(id, authentication);
        if (!"IMPORT".equals(job.direction())) throw new DataTransferException("TRANSFER_JOB_NOT_FOUND");
        List<String> statuses = requestedStatuses == null || requestedStatuses.isEmpty()
                ? List.of("VALID", "INVALID", "DUPLICATE", "CONFIRMED", "FAILED")
                : requestedStatuses.stream().distinct().toList();
        if (statuses.stream().anyMatch(status -> !List.of("VALID", "INVALID", "DUPLICATE", "CONFIRMED", "FAILED").contains(status)))
            throw new DataTransferException("VALIDATION_ERROR");
        int after = 0;
        if (cursor != null && !cursor.isBlank()) {
            try { after = Integer.parseInt(cursor); if (after < 0) throw new NumberFormatException(); }
            catch (NumberFormatException exception) { throw new DataTransferException("TRANSFER_CURSOR_INVALID"); }
        }
        List<RowRecord> records = repository.findRows(id, statuses, after, size + 1);
        boolean hasNext = records.size() > size;
        List<RowRecord> selected = hasNext ? records.subList(0, size) : records;
        List<Row> items = selected.stream().map(this::row).toList();
        String next = hasNext ? Integer.toString(selected.getLast().rowNumber()) : null;
        return new RowPage(items, size, next, hasNext);
    }

    private Row row(RowRecord record) {
        try {
            List<FieldError> errors = mapper.readValue(record.fieldErrorsJson(), new TypeReference<>() {});
            DuplicateCandidate duplicate = record.duplicateTargetId() == null ? null
                    : new DuplicateCandidate(record.duplicateTargetId(), "등록된 데이터");
            return new Row(record.rowNumber(), record.status(), record.maskedSummary(), errors, duplicate,
                    record.resultTargetId(), record.errorCode());
        } catch (Exception exception) {
            throw new DataTransferException("TRANSFER_JOB_READ_FAILED");
        }
    }

    private static UUID require(Authentication authentication, String permission) {
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> permission.equals(authority.getAuthority())))
            throw new DataTransferException(permission + "_DENIED");
        try { return UUID.fromString(authentication.getName()); }
        catch (RuntimeException exception) { throw new DataTransferException(permission + "_DENIED"); }
    }

    private static UUID requireAny(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(authority ->
                List.of("DATA_TRANSFER_IMPORT", "DATA_TRANSFER_EXPORT").contains(authority.getAuthority())))
            throw new DataTransferException("DATA_TRANSFER_READ_DENIED");
        try { return UUID.fromString(authentication.getName()); }
        catch (RuntimeException exception) { throw new DataTransferException("DATA_TRANSFER_READ_DENIED"); }
    }

    public record Template(String version, String csv) {}

    public static final class DataTransferException extends RuntimeException {
        private final String code;
        public DataTransferException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
