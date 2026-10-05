package com.ramiart.admin.datatransfer.application;

import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.student.application.StudentException;
import com.ramiart.admin.inquiry.application.InquiryDataProtector;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataTransferService {
    private static final Map<String, String> TEMPLATES = Map.of(
            "STUDENT", "studentName,birthday,joinedAt,guardianName,relationship,guardianPhone,guardianEmail,courseCode,classGroupCode\r\n",
            "PAYMENT", "billingId,studentId,yearMonth,paidOn,amount,method,memo\r\n",
            "ATTENDANCE", "attendanceSessionId,studentId,attendanceStatus\r\n");
    private final DataTransferRepository repository;
    private final ObjectMapper mapper;
    private final DataTransferStorage storage;
    private final InquiryDataProtector protector;
    private final DataTransferStudentWorker studentWorker;
    private final AuditRecorder audit;
    private final JdbcClient jdbc;

    public DataTransferService(DataTransferRepository repository, ObjectMapper mapper,
            DataTransferStorage storage, InquiryDataProtector protector, DataTransferStudentWorker studentWorker,
            AuditRecorder audit, JdbcClient jdbc) {
        this.repository = repository;
        this.mapper = mapper;
        this.storage = storage;
        this.protector = protector;
        this.studentWorker = studentWorker;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public Template template(String domain, Authentication authentication) {
        require(authentication, "DATA_TRANSFER_IMPORT");
        String normalized = domain == null ? "" : domain.trim().toUpperCase(java.util.Locale.ROOT);
        String csv = TEMPLATES.get(normalized);
        if (csv == null) throw new DataTransferException("TRANSFER_DOMAIN_INVALID");
        return new Template(normalized + "_V1", csv);
    }

    @Transactional
    public Job upload(String domain, String templateVersion, MultipartFile file, Authentication authentication) {
        UUID actor = require(authentication, "DATA_TRANSFER_IMPORT");
        String normalized = domain == null ? "" : domain.trim().toUpperCase(java.util.Locale.ROOT);
        Template template = template(normalized, authentication);
        if (!template.version().equals(templateVersion) || file == null || file.isEmpty())
            throw new DataTransferException("TRANSFER_FILE_INVALID");
        if (file.getSize() > 20L * 1024L * 1024L) throw new DataTransferException("TRANSFER_FILE_TOO_LARGE");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (java.io.IOException exception) { throw new DataTransferException("TRANSFER_FILE_INVALID"); }
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new DataTransferException("TRANSFER_FILE_INVALID");
        }
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        List<List<String>> csv;
        try { csv = DataTransferCsvParser.parse(source); }
        catch (DataTransferCsvParser.CsvFormatException exception) { throw new DataTransferException("TRANSFER_FILE_INVALID"); }
        List<String> headers = csv.getFirst();
        List<String> expected = List.of(template.csv().stripTrailing().split(",", -1));
        if (!headers.equals(expected) || csv.size() - 1 > 10_000) throw new DataTransferException("TRANSFER_FILE_INVALID");
        String hash = sha256(bytes);
        if (repository.hasActiveImport(normalized, hash)) throw new DataTransferException("TRANSFER_FILE_DUPLICATED");
        UUID id = UUID.randomUUID();
        String key = "data-transfers/" + id + ".csv";
        String name = sanitizeFileName(file.getOriginalFilename());
        boolean uploaded = false;
        try {
            storage.upload(key, bytes);
            uploaded = true;
            repository.createImport(id, normalized, template.version(), name, key, hash, bytes.length, actor);
            List<ImportedRow> rows = importRows(id, normalized, headers, csv.subList(1, csv.size()));
            repository.insertRows(id, rows);
            int valid = (int) rows.stream().filter(row -> "VALID".equals(row.status())).count();
            int invalid = (int) rows.stream().filter(row -> "INVALID".equals(row.status())).count();
            int duplicates = (int) rows.stream().filter(row -> "DUPLICATE".equals(row.status())).count();
            repository.markImportReady(id, rows.size(), valid, invalid, duplicates);
            return job(id, authentication);
        } catch (DataIntegrityViolationException exception) {
            if (uploaded) storage.delete(key);
            throw new DataTransferException("TRANSFER_FILE_DUPLICATED");
        } catch (RuntimeException exception) {
            if (uploaded) storage.delete(key);
            if (exception instanceof DataTransferException transferException) throw transferException;
            throw new DataTransferException("TRANSFER_IMPORT_FAILED");
        }
    }

    private List<ImportedRow> importRows(UUID jobId, String domain, List<String> headers, List<List<String>> csvRows) {
        List<ImportedRow> rows = new ArrayList<>(csvRows.size());
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < csvRows.size(); index++) {
            List<String> values = csvRows.get(index);
            java.util.LinkedHashMap<String, String> payload = new java.util.LinkedHashMap<>();
            List<FieldError> errors = new ArrayList<>();
            if (values.size() != headers.size()) errors.add(new FieldError("row", "COLUMN_COUNT_INVALID"));
            for (int column = 0; column < headers.size(); column++)
                payload.put(headers.get(column), column < values.size() ? values.get(column).trim() : "");
            validateRow(domain, payload, errors);
            String canonical;
            try { canonical = mapper.writeValueAsString(payload); }
            catch (Exception exception) { throw new DataTransferException("TRANSFER_IMPORT_FAILED"); }
            String dedup = protector.hash("data-transfer:v1:" + domain + ":" + canonical);
            if (!seen.add(dedup)) errors.add(new FieldError("row", "DUPLICATE_IN_FILE"));
            String status = errors.isEmpty() ? "VALID" : "INVALID";
            UUID duplicateTargetId = null;
            if ("VALID".equals(status) && "STUDENT".equals(domain)) {
                duplicateTargetId = studentWorker.duplicateCandidate(payload);
                if (duplicateTargetId != null) status = "DUPLICATE";
            }
            String errorJson;
            try { errorJson = mapper.writeValueAsString(errors); }
            catch (Exception exception) { throw new DataTransferException("TRANSFER_IMPORT_FAILED"); }
            String summary = domainLabel(domain) + " · " + (index + 1) + "행";
            rows.add(new ImportedRow(UUID.randomUUID(), index + 1, status, protector.protect(canonical), dedup,
                    summary, errorJson, duplicateTargetId, null));
        }
        return List.copyOf(rows);
    }

    private static void validateRow(String domain, Map<String, String> row, List<FieldError> errors) {
        switch (domain) {
            case "STUDENT" -> {
                required(row, "studentName", 100, errors);
                required(row, "guardianName", 100, errors);
                required(row, "guardianPhone", 30, errors);
                if (!Set.of("MOTHER", "FATHER", "GRANDPARENT", "GUARDIAN").contains(row.get("relationship")))
                    errors.add(new FieldError("relationship", "INVALID_VALUE"));
                if (row.get("guardianEmail") != null && !row.get("guardianEmail").isBlank()
                        && !row.get("guardianEmail").matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                    errors.add(new FieldError("guardianEmail", "INVALID_VALUE"));
                date(row, "birthday", errors);
                required(row, "joinedAt", 10, errors);
                date(row, "joinedAt", errors);
                for (String field : List.of("courseCode", "classGroupCode"))
                    if (row.get(field) != null && !row.get(field).isBlank())
                        errors.add(new FieldError(field, "NOT_SUPPORTED_FOR_IMPORT"));
            }
            case "PAYMENT" -> {
                if (!uuid(row.get("billingId")) && !uuid(row.get("studentId"))) errors.add(new FieldError("billingId", "TARGET_REQUIRED"));
                if (!row.get("yearMonth").matches("^\\d{4}-(0[1-9]|1[0-2])$")) errors.add(new FieldError("yearMonth", "INVALID_VALUE"));
                date(row, "paidOn", errors);
                try { if (new java.math.BigDecimal(row.get("amount")).signum() <= 0) errors.add(new FieldError("amount", "INVALID_VALUE")); }
                catch (NumberFormatException exception) { errors.add(new FieldError("amount", "INVALID_VALUE")); }
                if (!Set.of("CASH", "CARD", "TRANSFER", "OTHER").contains(row.get("method"))) errors.add(new FieldError("method", "INVALID_VALUE"));
            }
            case "ATTENDANCE" -> {
                if (!uuid(row.get("attendanceSessionId"))) errors.add(new FieldError("attendanceSessionId", "INVALID_VALUE"));
                if (!uuid(row.get("studentId"))) errors.add(new FieldError("studentId", "INVALID_VALUE"));
                if (!Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED").contains(row.get("attendanceStatus")))
                    errors.add(new FieldError("attendanceStatus", "INVALID_VALUE"));
            }
            default -> errors.add(new FieldError("domain", "INVALID_VALUE"));
        }
    }

    private static void required(Map<String, String> row, String field, int max, List<FieldError> errors) {
        String value = row.get(field);
        if (value == null || value.isBlank()) errors.add(new FieldError(field, "REQUIRED"));
        else if (value.length() > max) errors.add(new FieldError(field, "TOO_LONG"));
    }

    private static void date(Map<String, String> row, String field, List<FieldError> errors) {
        String value = row.get(field);
        if (value != null && !value.isBlank()) try { java.time.LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException exception) { errors.add(new FieldError(field, "INVALID_DATE")); }
    }

    private static boolean uuid(String value) {
        try { UUID.fromString(value); return true; } catch (RuntimeException exception) { return false; }
    }

    private static String domainLabel(String domain) {
        return switch (domain) { case "STUDENT" -> "원생"; case "PAYMENT" -> "납입"; case "ATTENDANCE" -> "출석"; default -> "데이터"; };
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "import.csv";
        String normalized = name.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.isBlank()) normalized = "import.csv";
        return normalized.substring(0, Math.min(255, normalized.length()));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    @Transactional
    public ConfirmResponse confirm(UUID jobId, ConfirmRequest request, UUID idempotencyKey,
            RequestMetadata metadata, Authentication authentication) {
        UUID actor = require(authentication, "DATA_TRANSFER_IMPORT");
        require(authentication, "STUDENT_WRITE");
        validateConfirm(request, idempotencyKey);
        String scope = "DATA_TRANSFER_CONFIRM:" + actor + ":" + jobId;
        String requestHash = protector.hash(canonicalConfirm(request));
        int claimed = jdbc.sql("""
                insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at)
                values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours')
                on conflict(scope,idempotency_key) do nothing
                """).param("scope", scope).param("key", idempotencyKey).param("hash", requestHash).update();
        if (claimed == 0) {
            Object[] prior = jdbc.sql("select request_hash,state,encrypted_response from idempotency_record where scope=:scope and idempotency_key=:key")
                    .param("scope", scope).param("key", idempotencyKey)
                    .query((row, index) -> new Object[] {row.getString(1), row.getString(2), row.getBytes(3)}).single();
            if (!requestHash.equals(prior[0])) throw new DataTransferException("IDEMPOTENCY_KEY_REUSED");
            if (!"COMPLETED".equals(prior[1]) || prior[2] == null)
                throw new DataTransferException("IDEMPOTENCY_IN_PROGRESS");
            return new ConfirmResponse(job(jobId, authentication), deserializeResults(protector.reveal((byte[]) prior[2])));
        }

        ConfirmJob job = repository.lockConfirmJob(jobId, actor)
                .orElseThrow(() -> new DataTransferException("TRANSFER_JOB_NOT_FOUND"));
        if (job.version() != request.jobVersion()) throw new DataTransferException("TRANSFER_JOB_VERSION_CONFLICT");
        if (!"STUDENT".equals(job.domain())) throw new DataTransferException("TRANSFER_DOMAIN_NOT_SUPPORTED");
        if (!List.of("READY", "PROCESSING", "PARTIAL").contains(job.status()))
            throw new DataTransferException("TRANSFER_ROW_NOT_CONFIRMABLE");
        List<ConfirmRow> rows = repository.lockConfirmRows(jobId, request.rowIds());
        if (rows.size() != request.rowIds().size()) throw new DataTransferException("TRANSFER_ROW_NOT_CONFIRMABLE");

        int confirmedDelta = 0;
        int failedDelta = 0;
        int duplicateDelta = 0;
        int validDelta = 0;
        int changed = 0;
        List<ConfirmResult> results = new ArrayList<>();
        for (ConfirmRow row : rows) {
            String duplicateAction = request.duplicateActions().get(row.id());
            if (duplicateAction != null && !"DUPLICATE".equals(row.status()))
                throw new DataTransferException("TRANSFER_ROW_NOT_CONFIRMABLE");
            if ("DUPLICATE".equals(row.status()) && "SKIP".equals(duplicateAction)) {
                results.add(new ConfirmResult(row.id(), "DUPLICATE", null, null));
                continue;
            }
            if (row.payloadCiphertext() == null) throw new DataTransferException("TRANSFER_ROW_NOT_CONFIRMABLE");
            Map<String, String> payload = decryptRow(row.payloadCiphertext());
            UUID rowKey = UUID.nameUUIDFromBytes((jobId + ":" + row.id()).getBytes(StandardCharsets.UTF_8));
            try {
                UUID target = studentWorker.create(payload, actor, rowKey, metadata,
                        "DUPLICATE".equals(row.status()) && "CREATE_NEW".equals(duplicateAction));
                repository.markRowConfirmed(row.id(), target);
                confirmedDelta++;
                if ("FAILED".equals(row.status())) failedDelta--;
                if ("DUPLICATE".equals(row.status())) { duplicateDelta--; validDelta++; }
                changed++;
                results.add(new ConfirmResult(row.id(), "CONFIRMED", target, null));
            } catch (StudentException exception) {
                if ("STUDENT_DUPLICATE_CANDIDATE".equals(exception.code()) && !"CREATE_NEW".equals(duplicateAction)) {
                    UUID candidate = firstDuplicateCandidate(exception);
                    if (candidate == null) {
                        repository.markRowFailed(row.id(), "STUDENT_IMPORT_REJECTED");
                        if ("VALID".equals(row.status())) failedDelta++;
                        if ("DUPLICATE".equals(row.status())) { duplicateDelta--; validDelta++; failedDelta++; }
                        results.add(new ConfirmResult(row.id(), "FAILED", null, "STUDENT_IMPORT_REJECTED"));
                    } else {
                        repository.markRowDuplicate(row.id(), candidate);
                        if ("VALID".equals(row.status())) { duplicateDelta++; validDelta--; }
                        if ("FAILED".equals(row.status())) { failedDelta--; duplicateDelta++; validDelta--; }
                        results.add(new ConfirmResult(row.id(), "DUPLICATE", null, null));
                    }
                    changed++;
                } else {
                    repository.markRowFailed(row.id(), "STUDENT_IMPORT_REJECTED");
                    if ("VALID".equals(row.status())) failedDelta++;
                    if ("DUPLICATE".equals(row.status())) { duplicateDelta--; validDelta++; failedDelta++; }
                    changed++;
                    results.add(new ConfirmResult(row.id(), "FAILED", null, "STUDENT_IMPORT_REJECTED"));
                }
            }
        }
        if (changed > 0 && !repository.finishConfirmation(jobId, job.version(), confirmedDelta,
                failedDelta, duplicateDelta, validDelta)) throw new DataTransferException("TRANSFER_JOB_VERSION_CONFLICT");
        if (changed > 0) audit.record(new Event(Instant.now(), metadata.requestId(), "MGT-DATA-TRANSFER", "OPERATION",
                "ADMIN", actor, null, "DATA_TRANSFER_ROWS_CONFIRMED", "DATA_TRANSFER_JOB", jobId, "SUCCESS", null,
                metadata.ipAddress(), metadata.userAgent(), Map.of("confirmedCount", confirmedDelta,
                        "failedCount", failedDelta, "duplicateCount", duplicateDelta)));
        ConfirmResponse response = new ConfirmResponse(job(jobId, authentication), List.copyOf(results));
        try {
            jdbc.sql("""
                    update idempotency_record set state='COMPLETED',resource_id=:job,response_status=200,
                        encrypted_response=:response where scope=:scope and idempotency_key=:key
                    """).param("job", jobId).param("response", protector.protect(mapper.writeValueAsString(results)))
                    .param("scope", scope).param("key", idempotencyKey).update();
        } catch (Exception exception) { throw new DataTransferException("TRANSFER_CONFIRM_FAILED"); }
        return response;
    }

    private static void validateConfirm(ConfirmRequest request, UUID key) {
        if (request == null || request.jobVersion() < 0 || key == null || request.rowIds() == null
                || request.rowIds().isEmpty() || request.rowIds().size() > 500
                || request.rowIds().stream().distinct().count() != request.rowIds().size()
                || request.duplicateActions() == null
                || !request.rowIds().containsAll(request.duplicateActions().keySet())
                || request.duplicateActions().values().stream().anyMatch(action -> !Set.of("CREATE_NEW", "SKIP").contains(action)))
            throw new DataTransferException("VALIDATION_ERROR");
    }

    private static String canonicalConfirm(ConfirmRequest request) {
        return request.jobVersion() + "|" + request.rowIds().stream().sorted().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(",")) + "|" + request.duplicateActions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Map<String, String> decryptRow(byte[] ciphertext) {
        try { return mapper.readValue(protector.reveal(ciphertext), new TypeReference<>() {}); }
        catch (Exception exception) { throw new DataTransferException("TRANSFER_ROW_NOT_CONFIRMABLE"); }
    }

    private static UUID firstDuplicateCandidate(StudentException exception) {
        Object candidates = exception.details().get("candidates");
        if (candidates instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
            Object id = map.get("id");
            try { return UUID.fromString(String.valueOf(id)); } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    private List<ConfirmResult> deserializeResults(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new DataTransferException("TRANSFER_CONFIRM_FAILED"); }
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
            return new Row(record.id(), record.rowNumber(), record.status(), record.maskedSummary(), errors, duplicate,
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
    public record ConfirmRequest(int jobVersion, List<UUID> rowIds, Map<UUID, String> duplicateActions) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}

    public static final class DataTransferException extends RuntimeException {
        private final String code;
        public DataTransferException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
