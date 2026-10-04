package com.ramiart.admin.tuition.application;

import com.ramiart.admin.tuition.application.TuitionBillingBatchRepository.Batch;
import com.ramiart.admin.tuition.application.TuitionBillingBatchRepository.Claim;
import com.ramiart.admin.tuition.application.TuitionBillingBatchRepository.Result;
import com.ramiart.admin.tuition.application.TuitionBillingService.BillingException;
import com.ramiart.admin.tuition.application.TuitionBillingService.PreviewCandidateState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TuitionBillingBatchService {
    private final TuitionBillingBatchRepository repository;
    private final TuitionBillingService billingService;
    private final TransactionTemplate rowTransaction;
    private final Clock clock;
    private final ZoneId studioZone;

    public TuitionBillingBatchService(TuitionBillingBatchRepository repository,TuitionBillingService billingService,
            PlatformTransactionManager transactionManager,Clock clock,
            @Value("${admin.dashboard.studio-zone:${ADMIN_STUDIO_ZONE:Asia/Seoul}}") String studioZone) {
        this.repository=repository; this.billingService=billingService; this.clock=clock; this.studioZone=ZoneId.of(studioZone);
        rowTransaction=new TransactionTemplate(transactionManager);
        rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String,Object> issue(Request body,UUID idempotencyKey,RequestMetadata metadata,Authentication authentication) {
        require(authentication,"TUITION_BILLING_WRITE");
        if(body==null||body.yearMonth()==null||body.previewVersion()==null||body.studentIds()==null||body.studentIds().isEmpty()||body.studentIds().size()>500
                ||body.studentIds().stream().anyMatch(java.util.Objects::isNull)
                ||body.studentIds().stream().distinct().count()!=body.studentIds().size())
            throw new BillingException("VALIDATION_ERROR");
        YearMonth month=parseMonth(body.yearMonth());
        YearMonth current=YearMonth.from(LocalDate.now(clock.withZone(studioZone)));
        if(month.isBefore(current.minusMonths(24))||month.isAfter(current.plusMonths(3))) throw new BillingException("BILLING_MONTH_INVALID");
        UUID actorId;
        try { actorId=UUID.fromString(authentication.getName()); }
        catch(IllegalArgumentException exception) { throw new BillingException("TUITION_BILLING_WRITE_DENIED"); }
        String scope="MGT-TUITION-BILLING-BATCH:"+actorId;
        String requestHash=hash(body.yearMonth()+"|"+body.previewVersion()+"|"+
                body.studentIds().stream().map(UUID::toString).reduce((left,right)->left+","+right).orElse(""));
        Claim previous=repository.findClaim(scope,idempotencyKey).orElse(null);
        if(previous!=null&&!requestHash.equals(previous.requestHash())) throw new BillingException("IDEMPOTENCY_KEY_REUSED");
        if(previous!=null&&!"PROCESSING".equals(previous.status())) return view(repository.find(previous.batchId()).orElseThrow());

        UUID previewToken;
        try { previewToken=UUID.fromString(body.previewVersion()); }
        catch(RuntimeException exception) { throw new BillingException("BILLING_PREVIEW_CHANGED"); }
        Map<UUID,PreviewCandidateState> expected=billingService.verifyPreviewToken(body.yearMonth(),previewToken,
                body.studentIds(),authentication);
        Claim claim=previous==null ? repository.claim(scope,idempotencyKey,requestHash,body.yearMonth(),body.studentIds().size(),actorId) : previous;
        if(!claim.created()&&!"PROCESSING".equals(claim.status())) return view(repository.find(claim.batchId()).orElseThrow());

        Batch existing=repository.find(claim.batchId()).orElseThrow();
        List<UUID> done=new ArrayList<>();
        existing.createdItems().forEach(item->done.add(item.studentId()));
        existing.existingItems().forEach(item->done.add(item.studentId()));
        existing.failedItems().forEach(item->done.add(item.studentId()));
        for(UUID studentId:body.studentIds()) {
            if(done.contains(studentId)) continue;
            PreviewCandidateState candidate=expected.get(studentId);
            try {
                Result result=rowTransaction.execute(status -> repository.issueOne(claim.batchId(),body.yearMonth(),actorId,
                        candidate,metadata.requestId(),metadata.ipAddress(),metadata.userAgent()));
                if(result==null) throw new IllegalStateException("billing result missing");
            } catch(BillingException exception) {
                repository.recordFailure(claim.batchId(),studentId,safeFailureCode(exception.code()));
            } catch(DataAccessException exception) {
                repository.recordFailure(claim.batchId(),studentId,"BILLING_BATCH_FAILED");
            }
        }
        repository.complete(claim.batchId());
        Batch result=repository.find(claim.batchId()).orElseThrow();
        if(result.created()+result.existing()+result.failed()!=result.requested()) throw new BillingException("BILLING_BATCH_FAILED");
        return view(result);
    }

    public Map<String,Object> find(UUID batchId,Authentication authentication) {
        require(authentication,"TUITION_BILLING_READ");
        return view(repository.find(batchId).orElseThrow(()->new BillingException("TUITION_BILLING_BATCH_NOT_FOUND")));
    }

    private static Map<String,Object> view(Batch batch) {
        return Map.of("batchId",batch.batchId(),"yearMonth",batch.yearMonth(),"status",batch.status(),
                "created",batch.createdItems().stream().map(TuitionBillingBatchService::createdItem).toList(),
                "existing",batch.existingItems().stream().map(TuitionBillingBatchService::createdItem).toList(),
                "failed",batch.failedItems().stream().map(item->Map.of("studentId",item.studentId(),"errorCode",item.errorCode())).toList(),
                "totals",Map.of("requested",batch.requested(),"created",batch.created(),"existing",batch.existing(),
                        "failed",batch.failed(),"createdAmount",batch.createdAmount()));
    }

    private static Map<String,Object> createdItem(Result item) {
        return Map.of("studentId",item.studentId(),"billingId",item.billingId(),"amount",item.amount());
    }

    private static String safeFailureCode(String code) {
        return switch(code) {
            case "BILLING_ASSIGNMENT_MISSING","BILLING_ASSIGNMENT_CONFLICT","BILLING_DUE_DATE_MISSING","BILLING_PREVIEW_CHANGED" -> code;
            default -> "BILLING_BATCH_FAILED";
        };
    }

    private static YearMonth parseMonth(String value) {
        try {
            if(value==null||!value.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new java.time.format.DateTimeParseException("invalid",value==null?"":value,0);
            return YearMonth.parse(value);
        } catch(java.time.format.DateTimeParseException exception) { throw new BillingException("BILLING_MONTH_INVALID"); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void require(Authentication auth,String permission) {
        if(auth==null||auth.getAuthorities().stream().noneMatch(a->permission.equals(a.getAuthority())))
            throw new BillingException("TUITION_BILLING_WRITE".equals(permission)?"TUITION_BILLING_WRITE_DENIED":"TUITION_BILLING_READ_DENIED");
    }

    public record Request(String yearMonth,List<UUID> studentIds,String previewVersion) {}
    public record RequestMetadata(String requestId,String ipAddress,String userAgent) {}
}
