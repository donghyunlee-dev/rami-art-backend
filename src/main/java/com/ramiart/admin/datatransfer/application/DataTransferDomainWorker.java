package com.ramiart.admin.datatransfer.application;

import com.ramiart.admin.attendance.application.AttendanceModels.AttendanceWrite;
import com.ramiart.admin.attendance.application.AttendanceService;
import com.ramiart.admin.tuition.application.TuitionPaymentService;
import com.ramiart.admin.tuition.application.TuitionPaymentService.CreateRequest;
import com.ramiart.admin.tuition.application.TuitionPaymentService.Metadata;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataTransferDomainWorker {
    private final TuitionPaymentService payments;
    private final AttendanceService attendance;
    private final JdbcClient jdbc;

    public DataTransferDomainWorker(TuitionPaymentService payments, AttendanceService attendance, JdbcClient jdbc) {
        this.payments = payments;
        this.attendance = attendance;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID createPayment(Map<String, String> row, UUID key, DataTransferService.RequestMetadata metadata,
            Authentication authentication) {
        String scope = "DATA_TRANSFER_PAYMENT:" + key;
        String hash = hash(row);
        int inserted = jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) " +
                "values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope", scope).param("key", key).param("hash", hash).update();
        if (inserted == 0) {
            var prior = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update")
                    .param("scope", scope).param("key", key)
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)}).single();
            if (!hash.equals(prior[0])) throw new DataTransferService.DataTransferException("IDEMPOTENCY_KEY_REUSED");
            if (!"COMPLETED".equals(prior[1]) || prior[2] == null)
                throw new DataTransferService.DataTransferException("IDEMPOTENCY_IN_PROGRESS");
            return (UUID) prior[2];
        }
        UUID billingId = resolveBilling(row);
        Integer version = jdbc.sql("select version from tuition_billing where id=:id for update")
                .param("id", billingId).query(Integer.class).single();
        Map<String, Object> result = payments.create(billingId, new CreateRequest(LocalDate.parse(row.get("paidOn")),
                Long.parseLong(row.get("amount")), row.get("method"), blankToNull(row.get("memo")), version), key,
                new Metadata(metadata.requestId(), metadata.ipAddress(), metadata.userAgent()), authentication);
        Object payment = result.get("payment");
        if (!(payment instanceof Map<?, ?> values) || !(values.get("paymentId") instanceof UUID id))
            throw new DataTransferService.DataTransferException("TRANSFER_CONFIRM_FAILED");
        jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=201 where scope=:scope and idempotency_key=:key")
                .param("id", id).param("scope", scope).param("key", key).update();
        return id;
    }

    private UUID resolveBilling(Map<String, String> row) {
        UUID billingId;
        if (notBlank(row.get("billingId"))) {
            billingId = UUID.fromString(row.get("billingId"));
        } else {
            billingId = jdbc.sql("select id from tuition_billing where student_id=:student and year_month=:month")
                    .param("student", UUID.fromString(row.get("studentId"))).param("month", row.get("yearMonth"))
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new DataTransferService.DataTransferException("TUITION_BILLING_NOT_FOUND"));
        }
        var target = jdbc.sql("select student_id,year_month from tuition_billing where id=:id")
                .param("id", billingId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new DataTransferService.DataTransferException("TUITION_BILLING_NOT_FOUND"));
        if (notBlank(row.get("studentId")) && !UUID.fromString(row.get("studentId")).toString().equals(target[0]))
            throw new DataTransferService.DataTransferException("TRANSFER_TARGET_MISMATCH");
        if (notBlank(row.get("yearMonth")) && !row.get("yearMonth").equals(target[1]))
            throw new DataTransferService.DataTransferException("TRANSFER_TARGET_MISMATCH");
        return billingId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID createAttendance(Map<String, String> row, UUID key, DataTransferService.RequestMetadata metadata,
            Authentication authentication) {
        UUID sessionId = UUID.fromString(row.get("attendanceSessionId"));
        UUID studentId = UUID.fromString(row.get("studentId"));
        var session = attendance.findById(sessionId, authentication);
        var target = session.students().stream().filter(student -> student.studentId().equals(studentId)).findFirst()
                .orElseThrow(() -> new DataTransferService.DataTransferException("ATTENDANCE_TARGET_NOT_FOUND"));
        attendance.save(sessionId, studentId, new AttendanceWrite("PRESENT", null, null, false,
                target.attendance() == null ? null : target.attendance().version(), session.version()), authentication,
                new AttendanceService.RequestMetadata(metadata.requestId(), metadata.ipAddress(), metadata.userAgent()), key);
        return jdbc.sql("select id from student_attendance where attendance_session_id=:session and student_id=:student")
                .param("session", sessionId).param("student", studentId).query(UUID.class).single();
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static String blankToNull(String value) { return notBlank(value) ? value.trim() : null; }
    private static String hash(Map<String, String> row) {
        try {
            String value = row.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("&"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
