package com.ramiart.admin.tuition.application;

import static com.ramiart.admin.tuition.application.TuitionBillingRepository.BillingDetail;
import static com.ramiart.admin.tuition.application.TuitionBillingRepository.BillingRow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import com.ramiart.admin.tuition.application.TuitionBillingRepository.PreviewStudent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TuitionBillingService {
    private static final List<String> STATUSES = List.of("ISSUED", "PARTIALLY_PAID", "PAID", "OVERDUE", "CREDIT");
    private final TuitionBillingRepository repository;
    private final TuitionPreviewSnapshotProtector previewProtector;
    private final Clock clock;
    private final ZoneId studioZone;

    public TuitionBillingService(TuitionBillingRepository repository, TuitionPreviewSnapshotProtector previewProtector,
            Clock clock,
            @Value("${admin.dashboard.studio-zone:${ADMIN_STUDIO_ZONE:Asia/Seoul}}") String studioZone) {
        this.repository = repository;
        this.previewProtector=previewProtector;
        this.clock = clock;
        this.studioZone = ZoneId.of(studioZone);
    }

    @Transactional
    public BillingPreview preview(String yearMonthInput,Authentication authentication) {
        require(authentication,"TUITION_BILLING_READ");
        YearMonth month=parseMonth(yearMonthInput);
        YearMonth current=YearMonth.from(LocalDate.now(clock.withZone(studioZone)));
        if(month.isBefore(current.minusMonths(24))||month.isAfter(current.plusMonths(3)))
            throw new BillingException("BILLING_MONTH_INVALID");
        List<PreviewStudent> rows=repository.preview(month);
        List<PreviewStudent> candidates=rows.stream().filter(TuitionBillingService::isCandidate).toList();
        if(candidates.size()>500) throw new BillingException("BILLING_PREVIEW_TOO_LARGE");
        List<Map<String,Object>> candidateViews=candidates.stream().map(row->previewCandidate(row,month)).toList();
        List<Map<String,Object>> excluded=rows.stream().filter(row->!candidates.contains(row))
                .map(row->Map.<String,Object>of("studentId",row.studentId(),"studentName",row.studentName(),
                        "reasonCode",row.assignmentCount()>1?"BILLING_ASSIGNMENT_CONFLICT":
                                row.assignmentCount()==0?"BILLING_ASSIGNMENT_MISSING":
                                        row.defaultDueDay()==null?"BILLING_DUE_DATE_MISSING":"BILLING_ASSIGNMENT_MISSING"))
                .toList();
        String snapshot=previewSnapshot(candidates,month);
        String hash=hashSnapshot(snapshot);
        UUID token=UUID.randomUUID();
        OffsetDateTime now=OffsetDateTime.now(clock).atZoneSameInstant(studioZone).toOffsetDateTime();
        OffsetDateTime expiresAt=now.plusMinutes(5);
        repository.savePreview(previewScope(authentication.getName(),month),token,hash,previewProtector.encrypt(snapshot),expiresAt);
        long selectedAmount=candidates.stream().filter(row->!row.alreadyIssued()).mapToLong(PreviewStudent::amount).sum();
        int selectedCount=(int)candidates.stream().filter(row->!row.alreadyIssued()).count();
        return new BillingPreview(month.toString(),token.toString(),expiresAt,month.atDay(1),candidateViews,excluded,
                Map.of("candidateCount",candidateViews.size(),"excludedCount",excluded.size(),
                        "selectedCount",selectedCount,"amount",selectedAmount));
    }

    private Map<String,Object> previewCandidate(PreviewStudent row,YearMonth month) {
        int dueDay=Math.min(row.defaultDueDay(),month.lengthOfMonth());
        LocalDate dueDate=month.atDay(dueDay);
        return Map.of("studentId",row.studentId(),"studentName",row.studentName(),"assignmentId",row.assignmentId(),
                "assignmentVersion",row.assignmentVersion(),"policyItemId",row.policyItemId(),
                "policyLabel",row.policyLabel(),"amount",row.amount(),"dueDate",dueDate,
                "alreadyIssued",row.alreadyIssued(),"selectedByDefault",!row.alreadyIssued());
    }

    private String previewHash(List<PreviewStudent> candidates,YearMonth month) {
        return hashSnapshot(previewSnapshot(candidates,month));
    }

    private static String previewSnapshot(List<PreviewStudent> candidates,YearMonth month) {
        StringBuilder value=new StringBuilder(month.toString());
        for(PreviewStudent row:candidates) value.append('|').append(row.studentId()).append('|').append(row.assignmentId())
                .append('|').append(row.assignmentVersion()).append('|').append(row.policyItemId()).append('|').append(row.amount())
                .append('|').append(month.atDay(Math.min(row.defaultDueDay(),month.lengthOfMonth())))
                .append('|').append(row.alreadyIssued());
        return value.toString();
    }

    private static String hashSnapshot(String snapshot) {
        try {
            String[] fields=snapshot.split("\\|",-1);
            StringBuilder candidateState=new StringBuilder(fields[0]);
            for(int index=1;index+6<fields.length;index+=7) {
                for(int field=0;field<6;field++) candidateState.append('|').append(fields[index+field]);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(candidateState.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("billing preview hash unavailable",exception);
        }
    }

    public String previewHashNow(String yearMonth,Authentication authentication) {
        YearMonth month=parseMonth(yearMonth);
        return previewHash(repository.preview(month).stream().filter(TuitionBillingService::isCandidate).toList(),month);
    }

    @Transactional(readOnly=true)
    public Map<UUID,PreviewCandidateState> verifyPreviewToken(String yearMonth,UUID token,List<UUID> selectedStudentIds,
            Authentication authentication) {
        require(authentication,"TUITION_BILLING_WRITE");
        YearMonth month=parseMonth(yearMonth);
        String scope=previewScope(authentication.getName(),month);
        OffsetDateTime now=OffsetDateTime.now(clock).atZoneSameInstant(studioZone).toOffsetDateTime();
        var saved=repository.findPreview(scope,token,now).orElseThrow(()->new BillingException("BILLING_PREVIEW_CHANGED"));
        String snapshot=previewProtector.decrypt(saved.encryptedResponse());
        if(!saved.requestHash().equals(hashSnapshot(snapshot))||!saved.requestHash().equals(previewHashNow(yearMonth,authentication)))
            throw new BillingException("BILLING_PREVIEW_CHANGED");
        String[] fields=snapshot.split("\\|",-1);
        Map<UUID,PreviewCandidateState> states=new LinkedHashMap<>();
        for(int index=1;index+6<fields.length;index+=7) {
            UUID studentId=UUID.fromString(fields[index]);
            states.put(studentId,new PreviewCandidateState(studentId,UUID.fromString(fields[index+1]),
                    Long.parseLong(fields[index+2]),UUID.fromString(fields[index+3]),Long.parseLong(fields[index+4]),
                    LocalDate.parse(fields[index+5]),Boolean.parseBoolean(fields[index+6])));
        }
        if(selectedStudentIds==null||selectedStudentIds.isEmpty()||selectedStudentIds.size()>500
                ||selectedStudentIds.stream().distinct().count()!=selectedStudentIds.size()
                ||selectedStudentIds.stream().anyMatch(id->!states.containsKey(id)||states.get(id).alreadyIssued()))
            throw new BillingException("VALIDATION_ERROR");
        Map<UUID,PreviewCandidateState> selected=new LinkedHashMap<>();
        for(UUID id:selectedStudentIds) selected.put(id,states.get(id));
        return Map.copyOf(selected);
    }

    private static boolean isCandidate(PreviewStudent row) {
        return row.assignmentCount()==1 && row.policyItemId()!=null && row.amount()!=null && row.defaultDueDay()!=null
                && ("PUBLISHED".equals(row.policyStatus())||"ARCHIVED".equals(row.policyStatus()));
    }

    public static String previewScope(String actorId,YearMonth month) {
        return "MGT-TUITION-BILLING-PREVIEW:"+actorId+":"+month;
    }

    private static YearMonth parseMonth(String value) {
        try {
            if(value==null||!value.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new DateTimeParseException("invalid",value==null?"":value,0);
            return YearMonth.parse(value);
        } catch(DateTimeParseException exception) {
            throw new BillingException("BILLING_MONTH_INVALID");
        }
    }

    @Transactional(readOnly = true)
    public BillingList list(String yearMonthInput, List<String> statusInput, int page, int size,
            Authentication authentication) {
        require(authentication, "TUITION_BILLING_READ");
        if (page < 0 || !List.of(10,20,50).contains(size)) throw new BillingException("VALIDATION_ERROR");
        List<String> statuses = normalizeStatuses(statusInput);
        YearMonth current = YearMonth.from(LocalDate.now(clock.withZone(studioZone)));
        YearMonth selected = null;
        if (yearMonthInput != null && !yearMonthInput.isBlank()) {
            try {
                if (!yearMonthInput.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new DateTimeParseException("invalid", yearMonthInput, 0);
                selected = YearMonth.parse(yearMonthInput);
            } catch (DateTimeParseException exception) {
                throw new BillingException("BILLING_MONTH_INVALID");
            }
            if (selected.isBefore(current.minusMonths(24)) || selected.isAfter(current.plusMonths(3)))
                throw new BillingException("BILLING_MONTH_INVALID");
        } else if (!statuses.equals(List.of("OVERDUE"))) {
            throw new BillingException("BILLING_MONTH_REQUIRED");
        }
        LocalDate today = LocalDate.now(clock.withZone(studioZone));
        LocalDate from = current.minusMonths(24).atDay(1);
        String month = selected == null ? null : selected.toString();
        boolean overdueOnly = selected == null;
        long total = repository.count(month, overdueOnly, statuses, from, today, today);
        List<Map<String,Object>> items = repository.list(month, overdueOnly, statuses, from, today, today,
                size, (long)page * size).stream().map(row -> listItem(row, today, authentication)).toList();
        long pages = total == 0 ? 0 : (total + size - 1) / size;
        return new BillingList(items, Map.of("number",page,"size",size,"totalElements",total,"totalPages",pages,
                "first",page==0,"last",pages==0 || page>=pages-1), repository.summarize(month, overdueOnly, statuses, from, today, today));
    }

    @Transactional(readOnly = true)
    public Map<String,Object> detail(UUID billingId, Authentication authentication) {
        require(authentication,"TUITION_BILLING_READ");
        BillingDetail detail = repository.detail(billingId).orElseThrow(() -> new BillingException("TUITION_BILLING_NOT_FOUND"));
        BillingRow row = detail.row();
        LocalDate today = LocalDate.now(clock.withZone(studioZone));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("billingId",row.billingId());
        result.put("student",Map.of("studentId",row.studentId(),"studentName",row.studentName(),"status",detail.studentStatus()));
        result.put("yearMonth",row.yearMonth());
        result.put("policySnapshot",Map.of("policyItemId",detail.policyItemId(),"policyLabel",row.policyLabel(),
                "monthlyAmount",detail.monthlyAmount(),"defaultDueDay",detail.defaultDueDay()));
        Map<String,Object> assignment = new LinkedHashMap<>();
        assignment.put("assignmentId",detail.assignmentId()); assignment.put("assignmentLabel",row.assignmentLabel());
        assignment.put("overrideAmount",detail.overrideAmount()); assignment.put("overrideReason",detail.overrideReason());
        assignment.put("assignmentVersion",detail.assignmentVersion()); result.put("assignmentSnapshot",assignment);
        result.put("amount",amounts(row)); result.put("dueDate",row.dueDate());
        result.put("paymentStatus",row.paymentStatus()); result.put("displayStatus",displayStatus(row,today));
        result.put("issued",Map.of("issuedAt",row.issuedAt(),"issuedByAdminId",detail.issuerId(),
                "issuedByName",detail.issuerName(),"batchId",detail.batchId()));
        result.put("version",row.version());
        List<String> actions = new ArrayList<>();
        if (has(authentication,"TUITION_PAYMENT_WRITE") && row.balance()>0) actions.add("RECORD_PAYMENT");
        if (has(authentication,"TUITION_PAYMENT_READ")) actions.add("VIEW_PAYMENTS");
        result.put("actions",actions);
        result.put("targetUrl","/admin/tuition-billings/"+row.billingId());
        return Map.copyOf(result);
    }

    private Map<String,Object> listItem(BillingRow row, LocalDate today, Authentication authentication) {
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("billingId",row.billingId()); item.put("studentId",row.studentId()); item.put("studentName",row.studentName());
        item.put("yearMonth",row.yearMonth()); item.put("policyLabel",row.policyLabel()); item.put("assignmentLabel",row.assignmentLabel());
        item.put("billedAmount",row.baseAmount()); item.put("paidAmount",row.paidAmount()); item.put("balance",row.balance());
        item.put("baseAmount",row.baseAmount()); item.put("confirmedAdjustmentAmount",row.adjustmentAmount());
        item.put("chargeAmount",row.chargeAmount()); item.put("confirmedPaymentAmount",row.paidAmount());
        item.put("confirmedRefundAmount",row.refundedAmount()); item.put("netPaidAmount",row.netPaidAmount());
        item.put("refundableAmount",Math.max(-row.balance(),0)); item.put("dueDate",row.dueDate());
        item.put("paymentStatus",row.paymentStatus()); item.put("displayStatus",displayStatus(row,today));
        item.put("issuedAt",row.issuedAt()); item.put("version",row.version());
        List<String> actions = new ArrayList<>(); actions.add("VIEW");
        if (has(authentication,"TUITION_PAYMENT_WRITE") && row.balance()>0) actions.add("RECORD_PAYMENT");
        item.put("actions",actions);
        return Map.copyOf(item);
    }

    private static Map<String,Object> amounts(BillingRow row) {
        return Map.of("billedAmount",row.baseAmount(),"baseAmount",row.baseAmount(),
                "confirmedAdjustmentAmount",row.adjustmentAmount(),"chargeAmount",row.chargeAmount(),
                "confirmedPaymentAmount",row.paidAmount(),"confirmedRefundAmount",row.refundedAmount(),
                "netPaidAmount",row.netPaidAmount(),"balance",row.balance(),
                "refundableAmount",Math.max(-row.balance(),0));
    }

    private static String displayStatus(BillingRow row, LocalDate today) {
        if (row.balance()<0) return "CREDIT";
        if (row.balance()==0) return "PAID";
        return row.dueDate().isBefore(today) ? "OVERDUE" : row.paymentStatus();
    }

    private static List<String> normalizeStatuses(List<String> input) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.stream().anyMatch(value -> value == null || !STATUSES.contains(value))
                || input.stream().distinct().count()!=input.size()) throw new BillingException("VALIDATION_ERROR");
        return List.copyOf(input);
    }

    private static void require(Authentication auth, String permission) {
        if (!has(auth,permission)) throw new BillingException(switch(permission) {
            case "TUITION_BILLING_WRITE" -> "TUITION_BILLING_WRITE_DENIED";
            default -> "TUITION_BILLING_READ_DENIED";
        });
    }
    private static boolean has(Authentication auth, String permission) {
        return auth!=null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    public record BillingList(List<Map<String,Object>> items, Map<String,Object> page, Map<String,Object> summary) {}
    public record BillingPreview(String yearMonth,String previewVersion,OffsetDateTime expiresAt,LocalDate basisDate,
            List<Map<String,Object>> candidates,List<Map<String,Object>> excluded,Map<String,Object> totals) {}
    public record PreviewCandidateState(UUID studentId,UUID assignmentId,long assignmentVersion,UUID policyItemId,
            long amount,LocalDate dueDate,boolean alreadyIssued) {}
    public static final class BillingException extends RuntimeException {
        private final String code;
        public BillingException(String code) { this.code=code; }
        public String code() { return code; }
    }
}
