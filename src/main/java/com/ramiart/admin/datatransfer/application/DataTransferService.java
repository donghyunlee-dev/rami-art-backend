package com.ramiart.admin.datatransfer.application;

import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.student.application.StudentException;
import com.ramiart.admin.attendance.application.AttendanceService.AttendanceException;
import com.ramiart.admin.tuition.application.TuitionPaymentService.TuitionPaymentException;
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
import java.util.LinkedHashMap;
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
    private final DataTransferDomainWorker domainWorker;
    private final AuditRecorder audit;
    private final JdbcClient jdbc;

    public DataTransferService(DataTransferRepository repository, ObjectMapper mapper,
            DataTransferStorage storage, InquiryDataProtector protector, DataTransferStudentWorker studentWorker,
            DataTransferDomainWorker domainWorker, AuditRecorder audit, JdbcClient jdbc) {
        this.repository = repository;
        this.mapper = mapper;
        this.storage = storage;
        this.protector = protector;
        this.studentWorker = studentWorker;
        this.domainWorker = domainWorker;
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
                boolean billingTarget = uuid(row.get("billingId"));
                boolean studentTarget = uuid(row.get("studentId"));
                if (!billingTarget && !studentTarget) errors.add(new FieldError("billingId", "TARGET_REQUIRED"));
                if (!billingTarget && (row.get("yearMonth") == null || !row.get("yearMonth").matches("^\\d{4}-(0[1-9]|1[0-2])$")))
                    errors.add(new FieldError("yearMonth", "INVALID_VALUE"));
                if (billingTarget && row.get("yearMonth") != null && !row.get("yearMonth").isBlank()
                        && !row.get("yearMonth").matches("^\\d{4}-(0[1-9]|1[0-2])$"))
                    errors.add(new FieldError("yearMonth", "INVALID_VALUE"));
                if (billingTarget && studentTarget) errors.add(new FieldError("billingId", "AMBIGUOUS_TARGET"));
                required(row, "paidOn", 10, errors);
                date(row, "paidOn", errors);
                try { if (new java.math.BigDecimal(row.get("amount")).signum() <= 0 || new java.math.BigDecimal(row.get("amount")).stripTrailingZeros().scale() > 0) errors.add(new FieldError("amount", "INVALID_VALUE")); }
                catch (NumberFormatException exception) { errors.add(new FieldError("amount", "INVALID_VALUE")); }
                if (!Set.of("CASH", "CARD", "TRANSFER", "OTHER").contains(row.get("method"))) errors.add(new FieldError("method", "INVALID_VALUE"));
                if (row.get("memo") != null && row.get("memo").trim().length() > 300) errors.add(new FieldError("memo", "TOO_LONG"));
            }
            case "ATTENDANCE" -> {
                if (!uuid(row.get("attendanceSessionId"))) errors.add(new FieldError("attendanceSessionId", "INVALID_VALUE"));
                if (!uuid(row.get("studentId"))) errors.add(new FieldError("studentId", "INVALID_VALUE"));
                if (!Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED").contains(row.get("attendanceStatus")))
                    errors.add(new FieldError("attendanceStatus", "INVALID_VALUE"));
                else if (!"PRESENT".equals(row.get("attendanceStatus")))
                    errors.add(new FieldError("attendanceStatus", "NOT_SUPPORTED_FOR_IMPORT"));
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
        validateConfirm(request, idempotencyKey);
        ConfirmJob job = repository.lockConfirmJob(jobId, actor)
                .orElseThrow(() -> new DataTransferException("TRANSFER_JOB_NOT_FOUND"));
        String domainPermission = switch (job.domain()) {
            case "STUDENT" -> "STUDENT_WRITE";
            case "PAYMENT" -> "TUITION_PAYMENT_WRITE";
            case "ATTENDANCE" -> "ATTENDANCE_WRITE";
            default -> throw new DataTransferException("TRANSFER_DOMAIN_NOT_SUPPORTED");
        };
        require(authentication, domainPermission);
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

        if (job.version() != request.jobVersion()) throw new DataTransferException("TRANSFER_JOB_VERSION_CONFLICT");
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
                UUID target = switch (job.domain()) {
                    case "STUDENT" -> studentWorker.create(payload, actor, rowKey, metadata,
                            "DUPLICATE".equals(row.status()) && "CREATE_NEW".equals(duplicateAction));
                    case "PAYMENT" -> domainWorker.createPayment(payload, rowKey, metadata, authentication);
                    case "ATTENDANCE" -> domainWorker.createAttendance(payload, rowKey, metadata, authentication);
                    default -> throw new DataTransferException("TRANSFER_DOMAIN_NOT_SUPPORTED");
                };
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
            } catch (TuitionPaymentException | AttendanceException | DataTransferException exception) {
                repository.markRowFailed(row.id(), exception.getMessage());
                if ("VALID".equals(row.status())) failedDelta++;
                changed++;
                results.add(new ConfirmResult(row.id(), "FAILED", null, exception.getMessage()));
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
        require(authentication, "EXPORT".equals(record.direction()) ? "DATA_TRANSFER_EXPORT" : "DATA_TRANSFER_IMPORT");
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

    @Transactional
    public ExportPreview previewExport(ExportRequest request, Authentication authentication) {
        UUID actor = require(authentication, "DATA_TRANSFER_EXPORT");
        ExportDefinition definition = exportDefinition(request);
        List<Map<String, String>> rows = exportRows(request.domain().trim().toUpperCase(java.util.Locale.ROOT), request.filters());
        if (rows.size() > 10_000) throw new DataTransferException("TRANSFER_EXPORT_TOO_LARGE");
        UUID token = UUID.randomUUID();
        String hash = exportRequestHash(request);
        String scope = "DATA_TRANSFER_EXPORT_PREVIEW:" + actor;
        jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,response_status,expires_at) " +
                "values(:scope,:key,:hash,'COMPLETED',200,statement_timestamp()+interval '5 minutes')")
                .param("scope", scope).param("key", token).param("hash", hash).update();
        jdbc.sql("update idempotency_record set encrypted_response=:snapshot where scope=:scope and idempotency_key=:key")
                .param("snapshot", protector.protect(rows.size() + ":" + exportRowsFingerprint(rows)))
                .param("scope", scope).param("key", token).update();
        return new ExportPreview(definition.fields(), rows.size(), token, Instant.now().plusSeconds(300));
    }

    @Transactional
    public Job createExport(ExportRequest request, UUID idempotencyKey, Authentication authentication) {
        UUID actor = require(authentication, "DATA_TRANSFER_EXPORT");
        if (idempotencyKey == null || request == null || request.previewToken() == null) throw new DataTransferException("VALIDATION_ERROR");
        ExportDefinition definition = exportDefinition(request);
        String requestHash = exportRequestHash(request);
        String createScope = "DATA_TRANSFER_EXPORT:" + actor;
        int claimed = jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) " +
                "values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope", createScope).param("key", idempotencyKey).param("hash", requestHash).update();
        if (claimed == 0) {
            var prior = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                    .param("scope", createScope).param("key", idempotencyKey)
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)}).single();
            if (!requestHash.equals(prior[0])) throw new DataTransferException("IDEMPOTENCY_KEY_REUSED");
            if (!"COMPLETED".equals(prior[1]) || prior[2] == null) throw new DataTransferException("IDEMPOTENCY_IN_PROGRESS");
            return job((UUID) prior[2], authentication);
        }
        String previewScope = "DATA_TRANSFER_EXPORT_PREVIEW:" + actor;
        var preview = jdbc.sql("select request_hash,state,encrypted_response from idempotency_record where scope=:scope and idempotency_key=:key and expires_at>statement_timestamp() for update")
                .param("scope", previewScope).param("key", request.previewToken())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBytes(3)}).optional()
                .orElseThrow(() -> new DataTransferException("TRANSFER_PREVIEW_EXPIRED"));
        if (!requestHash.equals(preview[0]) || !"COMPLETED".equals(preview[1]))
            throw new DataTransferException("TRANSFER_PREVIEW_INVALID");
        String domain = request.domain().trim().toUpperCase(java.util.Locale.ROOT);
        List<Map<String, String>> rows = exportRows(domain, request.filters());
        if (rows.size() > 10_000) throw new DataTransferException("TRANSFER_EXPORT_TOO_LARGE");
        String previewSnapshot = protector.reveal((byte[]) preview[2]);
        if (!(rows.size() + ":" + exportRowsFingerprint(rows)).equals(previewSnapshot))
            throw new DataTransferException("TRANSFER_PREVIEW_STALE");
        byte[] csv = renderCsv(definition.headers(), rows).getBytes(StandardCharsets.UTF_8);
        if (csv.length > 20L * 1024 * 1024) throw new DataTransferException("TRANSFER_FILE_TOO_LARGE");
        UUID id = UUID.randomUUID();
        String key = "data-transfers/" + id + ".csv";
        String hash = sha256(csv);
        boolean uploaded = false;
        try {
            storage.upload(key, csv);
            uploaded = true;
            jdbc.sql("insert into data_transfer_job(id,direction,domain,status,storage_key,sha256,file_size,filter_snapshot,purpose,total_count,valid_count,confirmed_count,expires_at,created_by) " +
                    "values(:id,'EXPORT',:domain,'COMPLETED',:key,:hash,:size,cast(:filters as jsonb),:purpose,:count,:count,:count,statement_timestamp()+interval '24 hours',:actor)")
                    .param("id", id).param("domain", domain).param("key", key).param("hash", hash)
                    .param("size", csv.length).param("filters", mapper.writeValueAsString(request.filters()))
                    .param("purpose", request.purpose().trim()).param("count", rows.size()).param("actor", actor).update();
            jdbc.sql("update idempotency_record set state='FAILED' where scope=:scope and idempotency_key=:key")
                    .param("scope", previewScope).param("key", request.previewToken()).update();
            jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:job,response_status=201 where scope=:scope and idempotency_key=:key")
                    .param("job", id).param("scope", createScope).param("key", idempotencyKey).update();
            audit.record(new Event(Instant.now(), "data-transfer-export", "MGT-DATA-TRANSFER", "OPERATION", "ADMIN",
                    actor, null, "DATA_TRANSFER_EXPORT_CREATED", "DATA_TRANSFER_JOB", id, "SUCCESS", null,
                    null, null, Map.of("domain", request.domain(), "preset", request.preset(), "recordCount", rows.size())));
            return job(id, authentication);
        } catch (Exception exception) {
            if (uploaded) storage.delete(key);
            if (exception instanceof DataTransferException transferException) throw transferException;
            throw new DataTransferException("TRANSFER_EXPORT_FAILED");
        }
    }

    @Transactional(readOnly = true)
    public DownloadUrl downloadUrl(UUID id, Authentication authentication) {
        UUID actor = require(authentication, "DATA_TRANSFER_EXPORT");
        var record = jdbc.sql("select storage_key,expires_at,status,direction from data_transfer_job where id=:id and created_by=:actor")
                .param("id", id).param("actor", actor)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, java.time.OffsetDateTime.class), rs.getString(3), rs.getString(4)})
                .optional().orElseThrow(() -> new DataTransferException("TRANSFER_JOB_NOT_FOUND"));
        java.time.OffsetDateTime expiresAt = (java.time.OffsetDateTime) record[1];
        if (!"EXPORT".equals(record[3]) || !List.of("COMPLETED", "PARTIAL").contains(record[2]))
            throw new DataTransferException("TRANSFER_JOB_NOT_FOUND");
        if (!expiresAt.isAfter(java.time.OffsetDateTime.now())) throw new DataTransferException("TRANSFER_FILE_EXPIRED");
        int expiresIn = (int) Math.min(300, Math.max(1, java.time.Duration.between(java.time.OffsetDateTime.now(), expiresAt).toSeconds()));
        try { return new DownloadUrl(storage.createSignedUrl((String) record[0], expiresIn), Instant.now().plusSeconds(expiresIn)); }
        catch (RuntimeException exception) { throw new DataTransferException("TRANSFER_STORAGE_UNAVAILABLE"); }
    }

    private ExportDefinition exportDefinition(ExportRequest request) {
        if (request == null || request.domain() == null || request.preset() == null || request.filters() == null
                || request.purpose() == null || request.purpose().trim().length() < 5 || request.purpose().trim().length() > 300)
            throw new DataTransferException("VALIDATION_ERROR");
        if (!"ANONYMIZED".equals(request.preset())) throw new DataTransferException("TRANSFER_PRESET_NOT_SUPPORTED");
        String domain = request.domain().trim().toUpperCase(java.util.Locale.ROOT);
        Set<String> allowedFilters = switch (domain) {
            case "STUDENT", "ATTENDANCE" -> Set.of("from", "to", "status");
            case "PAYMENT" -> Set.of("from", "to", "method", "status");
            default -> Set.of();
        };
        if (!allowedFilters.containsAll(request.filters().keySet()))
            throw new DataTransferException("VALIDATION_ERROR");
        String selectedStatus = filterValue(request.filters(), "status");
        if (selectedStatus != null && ("STUDENT".equals(domain) && !Set.of("ACTIVE", "PAUSED", "GRADUATED", "DROPPED").contains(selectedStatus)
                || "ATTENDANCE".equals(domain) && !Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED").contains(selectedStatus)
                || "PAYMENT".equals(domain) && !Set.of("CONFIRMED", "CANCELLED").contains(selectedStatus)))
            throw new DataTransferException("VALIDATION_ERROR");
        String selectedMethod = filterValue(request.filters(), "method");
        if (selectedMethod != null && !Set.of("CASH", "TRANSFER", "CARD", "OTHER").contains(selectedMethod))
            throw new DataTransferException("VALIDATION_ERROR");
        String from = filterValue(request.filters(), "from");
        String to = filterValue(request.filters(), "to");
        if (from != null && to != null && from.compareTo(to) > 0) throw new DataTransferException("VALIDATION_ERROR");
        return switch (domain) {
            case "STUDENT" -> new ExportDefinition(List.of("studentKey", "joinedAt", "status"), List.of("studentKey", "joinedAt", "status"));
            case "PAYMENT" -> new ExportDefinition(List.of("billingKey", "paidOn", "amount", "method"), List.of("billingKey", "paidOn", "amount", "method"));
            case "ATTENDANCE" -> new ExportDefinition(List.of("sessionKey", "attendanceDate", "studentKey", "status"), List.of("sessionKey", "attendanceDate", "studentKey", "status"));
            default -> throw new DataTransferException("TRANSFER_DOMAIN_INVALID");
        };
    }

    private List<Map<String, String>> exportRows(String domain, Map<String, String> filters) {
        String from = filterValue(filters, "from"), to = filterValue(filters, "to"), status = filterValue(filters, "status"), method = filterValue(filters, "method");
        String dateColumn = switch (domain) { case "STUDENT" -> "joined_at"; case "PAYMENT" -> "paid_on"; default -> "attendance_date"; };
        String query = switch (domain) {
            case "STUDENT" -> "select id,joined_at::text value_date,status from student where private_purged_at is null";
            case "PAYMENT" -> "select billing_id id,paid_on::text value_date,status,amount::text amount,method from tuition_payment where true";
            case "ATTENDANCE" -> "select session.id,session.attendance_date::text value_date,attendance.student_id,attendance.status from student_attendance attendance join attendance_session session on session.id=attendance.attendance_session_id where true";
            default -> throw new DataTransferException("TRANSFER_DOMAIN_INVALID");
        };
        StringBuilder sql = new StringBuilder(query);
        if (from != null) sql.append(" and ").append(dateColumn).append(" >= cast(:from as date)");
        if (to != null) sql.append(" and ").append(dateColumn).append(" <= cast(:to as date)");
        if (status != null && "STUDENT".equals(domain)) sql.append(" and status=:status");
        if (status != null && "ATTENDANCE".equals(domain)) sql.append(" and attendance.status=:status");
        if (status != null && "PAYMENT".equals(domain)) sql.append(" and status=:status");
        if (method != null && "PAYMENT".equals(domain)) sql.append(" and method=:method");
        sql.append(" order by ").append(dateColumn).append(",id limit 10001");
        var spec = jdbc.sql(sql.toString());
        if (from != null) spec = spec.param("from", from);
        if (to != null) spec = spec.param("to", to);
        if (status != null) spec = spec.param("status", status);
        if (method != null && "PAYMENT".equals(domain)) spec = spec.param("method", method);
        return spec.query((rs, n) -> {
            Map<String, String> row = new LinkedHashMap<>();
            UUID id = rs.getObject(1, UUID.class);
            String date = rs.getString(2);
            if ("STUDENT".equals(domain)) {
                row.put("studentKey", pseudonym("student", id)); row.put("joinedAt", date); row.put("status", rs.getString(3));
            } else if ("PAYMENT".equals(domain)) {
                row.put("billingKey", pseudonym("billing", id)); row.put("paidOn", date); row.put("amount", rs.getString(4)); row.put("method", rs.getString(5));
            } else {
                UUID studentId = rs.getObject(3, UUID.class);
                row.put("sessionKey", pseudonym("session", id)); row.put("attendanceDate", date);
                row.put("studentKey", pseudonym("student", studentId)); row.put("status", rs.getString(4));
            }
            return row;
        }).list();
    }

    private String pseudonym(String type, UUID id) { return type.substring(0, 3).toUpperCase(java.util.Locale.ROOT) + "-" + protector.hash(type + ":" + id).substring(0, 20); }
    private static String filterValue(Map<String, String> filters, String key) {
        Object value = filters.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > 20 || text.isBlank()) throw new DataTransferException("VALIDATION_ERROR");
        if (Set.of("from", "to").contains(key)) try { java.time.LocalDate.parse(text); }
        catch (RuntimeException exception) { throw new DataTransferException("VALIDATION_ERROR"); }
        return text;
    }
    private static String exportRequestHash(ExportRequest request) {
        try {
            String canonical = request.domain().trim().toUpperCase(java.util.Locale.ROOT) + "|" + request.preset() + "|"
                    + new java.util.TreeMap<>(request.filters()) + "|" + request.purpose().trim();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new DataTransferException("VALIDATION_ERROR"); }
    }
    private static String renderCsv(List<String> headers, List<Map<String, String>> rows) {
        StringBuilder csv = new StringBuilder(String.join(",", headers)).append("\r\n");
        for (Map<String, String> row : rows) csv.append(headers.stream().map(header -> csvCell(row.get(header)))
                .collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return csv.toString();
    }
    private static String exportRowsFingerprint(List<Map<String, String>> rows) {
        try {
            String canonical = rows.stream().map(row -> new java.util.TreeMap<>(row).toString())
                    .collect(java.util.stream.Collectors.joining("\n"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
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
    public record ExportRequest(String domain, String preset, Map<String, String> filters, String purpose, UUID previewToken) {}
    public record ExportPreview(List<String> fields, int candidateCount, UUID previewToken, Instant expiresAt) {}
    public record DownloadUrl(String url, Instant expiresAt) {}
    private record ExportDefinition(List<String> headers, List<String> fields) {}
    public record ConfirmRequest(int jobVersion, List<UUID> rowIds, Map<UUID, String> duplicateActions) {}
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}

    public static final class DataTransferException extends RuntimeException {
        private final String code;
        public DataTransferException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
