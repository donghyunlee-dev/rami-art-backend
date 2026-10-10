package com.ramiart.admin.tuition.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.tuition.application.TuitionAdjustmentRepository.Adjustment;
import com.ramiart.admin.tuition.application.TuitionAdjustmentRepository.Billing;
import com.ramiart.admin.tuition.application.TuitionAdjustmentRepository.Refund;
import com.ramiart.admin.tuition.infrastructure.JdbcTuitionAdjustmentRepository.TuitionAdjustmentException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TuitionAdjustmentService {
    private final TuitionAdjustmentRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;
    public TuitionAdjustmentService(TuitionAdjustmentRepository repository,AuditRecorder audit,Clock clock){this.repository=repository;this.audit=audit;this.clock=clock;}

    @Transactional(readOnly=true)
    public Map<String,Object> history(UUID billingId,Authentication auth){
        require(auth,"TUITION_ADJUSTMENT_READ");Billing billing=repository.findBilling(billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        return historyView(billing,repository.adjustments(billingId),repository.refunds(billingId));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> preview(UUID billingId,PreviewRequest request,Authentication auth){
        require(auth,"TUITION_ADJUSTMENT_READ");validatePreview(request);
        Billing billing=repository.findBilling(billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        long adjustment=billing.adjustmentAmount()+(request.type()==null?0:request.signedAmount());
        long charge=billing.baseAmount()+adjustment;
        if(charge<0)throw new TuitionAdjustmentException("TUITION_CHARGE_NEGATIVE");
        long balance=charge-billing.paymentAmount()+billing.refundAmount();
        long maxRefund=Math.max(-balance,0);
        if(request.refundAmount()!=null&&request.refundAmount()>maxRefund)throw new TuitionAdjustmentException("TUITION_REFUND_EXCEEDS_CREDIT");
        if(request.paymentId()!=null){
            Long version=repository.paymentVersion(request.paymentId(),billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_PAYMENT_NOT_FOUND"));
            if(request.paymentVersion()==null||version.longValue()!=request.paymentVersion())throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
            if(request.refundAmount()!=null&&request.refundAmount()>repository.paymentRefundable(request.paymentId()))throw new TuitionAdjustmentException("TUITION_REFUND_EXCEEDS_CREDIT");
        }
        long refund=request.refundAmount()==null?0:request.refundAmount();
        Map<String,Object> result=new LinkedHashMap<>();result.put("before",summary(billing));
        result.put("after",Map.of("baseAmount",billing.baseAmount(),"confirmedAdjustmentAmount",adjustment,"chargeAmount",charge,
                "confirmedPaymentAmount",billing.paymentAmount(),"confirmedRefundAmount",billing.refundAmount()+refund,
                "netPaidAmount",billing.paymentAmount()-billing.refundAmount()-refund,"balance",balance+refund,
                "refundableAmount",Math.max(-(balance+refund),0),"displayStatus",displayStatus(balance+refund,billing.dueDate())));
        result.put("valid",true);result.put("requiresRefund",maxRefund>0);return Map.copyOf(result);
    }
    @Transactional
    public Map<String,Object> adjust(UUID billingId,AdjustmentRequest request,UUID key,Metadata metadata,Authentication auth){
        require(auth,"TUITION_ADJUSTMENT_WRITE");validateAdjustment(request,key);UUID actor=actor(auth);
        Billing billing=repository.lockBilling(billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        String scope="MGT-TUITION-ADJUSTMENT:"+actor;String requestHash=hash(billingId+"|"+request.type()+"|"+request.signedAmount()+"|"+request.reason().trim()+"|"+request.billingVersion());
        var claim=repository.claim(scope,key,requestHash);if(!claim.created())return history(billingId,auth);
        checkBillingVersion(billing,request.billingVersion());long nextAdjustment=billing.adjustmentAmount()+request.signedAmount();
        long charge=billing.baseAmount()+nextAdjustment;if(charge<0)throw new TuitionAdjustmentException("TUITION_CHARGE_NEGATIVE");
        long nextBalance=charge-billing.paymentAmount()+billing.refundAmount();String status=status(nextBalance,billing.paymentAmount());
        UUID adjustmentId=UUID.randomUUID();repository.insertAdjustment(adjustmentId,billingId,request.type(),request.signedAmount(),request.reason().trim(),actor,
                billing.version(),nextAdjustment,billing.paymentAmount(),billing.refundAmount(),status);
        audit.record(event(metadata,actor,"TUITION_ADJUSTMENT_CREATED",billingId,"ADJUSTMENT",Map.of("type",request.type(),"signedAmount",request.signedAmount())));
        repository.complete(scope,key,adjustmentId,201);return history(billingId,auth);
    }
    @Transactional
    public Map<String,Object> cancelAdjustment(UUID adjustmentId,CancelRequest request,UUID key,Metadata metadata,Authentication auth){
        require(auth,"TUITION_ADJUSTMENT_WRITE");validateCancel(request,key);UUID actor=actor(auth);
        Adjustment target=repository.lockAdjustment(adjustmentId).orElseThrow(()->new TuitionAdjustmentException("TUITION_ADJUSTMENT_NOT_FOUND"));
        Billing billing=repository.lockBilling(target.billingId()).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        String reason=request.reason().trim(),scope="MGT-TUITION-ADJUSTMENT-CANCEL:"+actor;
        String requestHash=hash(adjustmentId+"|"+reason+"|"+request.targetVersion()+"|"+request.billingVersion());
        var claim=repository.claim(scope,key,requestHash);if(!claim.created())return history(billing.id(),auth);
        checkBillingVersion(billing,request.billingVersion());if(target.version()!=request.targetVersion())throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
        if(!"CONFIRMED".equals(target.status()))throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_ALREADY_CANCELLED");
        long nextAdjustment=billing.adjustmentAmount()-target.signedAmount(),charge=billing.baseAmount()+nextAdjustment;
        if(charge<0)throw new TuitionAdjustmentException("TUITION_CHARGE_NEGATIVE");long balance=charge-billing.paymentAmount()+billing.refundAmount();
        repository.cancelAdjustment(adjustmentId,actor,reason,target.version(),billing.version(),nextAdjustment,billing.paymentAmount(),billing.refundAmount(),status(balance,billing.paymentAmount()));
        audit.record(event(metadata,actor,"TUITION_ADJUSTMENT_CANCELLED",billing.id(),"ADJUSTMENT_CANCEL",Map.of("adjustmentId",adjustmentId)));
        repository.complete(scope,key,adjustmentId,200);return history(billing.id(),auth);
    }
    @Transactional
    public Map<String,Object> refund(UUID billingId,RefundRequest request,UUID key,Metadata metadata,Authentication auth){
        require(auth,"TUITION_REFUND_WRITE");validateRefund(request,key);UUID actor=actor(auth);
        Billing billing=repository.lockBilling(billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        String scope="MGT-TUITION-REFUND:"+actor;String requestHash=hash(billingId+"|"+request.amount()+"|"+request.refundedOn()+"|"+request.method()+"|"+request.paymentId()+"|"+request.paymentVersion()+"|"+request.reason().trim()+"|"+request.billingVersion());
        var claim=repository.claim(scope,key,requestHash);if(!claim.created())return history(billingId,auth);
        checkBillingVersion(billing,request.billingVersion());
        if(request.refundedOn().isAfter(LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")))))throw new TuitionAdjustmentException("VALIDATION_ERROR");
        if(request.amount()>billing.refundable())throw new TuitionAdjustmentException("TUITION_REFUND_EXCEEDS_CREDIT");
        if(request.paymentId()!=null){
            Long version=repository.paymentVersion(request.paymentId(),billingId).orElseThrow(()->new TuitionAdjustmentException("TUITION_PAYMENT_NOT_FOUND"));
            if(request.paymentVersion()==null||version.longValue()!=request.paymentVersion())throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
            if(request.amount()>repository.paymentRefundable(request.paymentId()))throw new TuitionAdjustmentException("TUITION_REFUND_EXCEEDS_CREDIT");
        }
        UUID refundId=UUID.randomUUID(),entryId=UUID.randomUUID();long nextRefund=billing.refundAmount()+request.amount();
        long nextBalance=billing.charge()-billing.paymentAmount()+nextRefund;
        repository.insertRefund(refundId,entryId,billingId,request.paymentId(),request.refundedOn(),request.amount(),request.method(),request.reason().trim(),actor,
                billing.version(),nextRefund,billing.paymentAmount(),status(nextBalance,billing.paymentAmount()));
        audit.record(event(metadata,actor,"TUITION_REFUND_CREATED",billingId,"REFUND",Map.of("amount",request.amount(),"method",request.method())));
        repository.complete(scope,key,refundId,201);return history(billingId,auth);
    }
    @Transactional
    public Map<String,Object> cancelRefund(UUID refundId,CancelRequest request,UUID key,Metadata metadata,Authentication auth){
        require(auth,"TUITION_REFUND_WRITE");validateCancel(request,key);UUID actor=actor(auth);
        Refund target=repository.lockRefund(refundId).orElseThrow(()->new TuitionAdjustmentException("TUITION_REFUND_NOT_FOUND"));
        Billing billing=repository.lockBilling(target.billingId()).orElseThrow(()->new TuitionAdjustmentException("TUITION_BILLING_NOT_FOUND"));
        String reason=request.reason().trim(),scope="MGT-TUITION-REFUND-CANCEL:"+actor;
        String requestHash=hash(refundId+"|"+reason+"|"+request.targetVersion()+"|"+request.billingVersion());
        var claim=repository.claim(scope,key,requestHash);if(!claim.created())return history(billing.id(),auth);
        checkBillingVersion(billing,request.billingVersion());if(target.version()!=request.targetVersion())throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");
        if(!"CONFIRMED".equals(target.status()))throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_ALREADY_CANCELLED");
        long nextRefund=billing.refundAmount()-target.amount();long nextBalance=billing.charge()-billing.paymentAmount()+nextRefund;
        repository.cancelRefund(refundId,actor,reason,target.version(),billing.version(),nextRefund,billing.paymentAmount(),status(nextBalance,billing.paymentAmount()));
        audit.record(event(metadata,actor,"TUITION_REFUND_CANCELLED",billing.id(),"REFUND_CANCEL",Map.of("refundId",refundId,"amount",target.amount())));
        repository.complete(scope,key,refundId,200);return history(billing.id(),auth);
    }
    private static Map<String,Object> historyView(Billing b,List<Adjustment> adjustments,List<Refund> refunds){
        Map<String,Object> view=new LinkedHashMap<>();view.put("billing",summary(b));
        view.put("adjustments",adjustments.stream().map(a->fields("adjustmentId",a.id(),"type",a.type(),"signedAmount",a.signedAmount(),"reason",a.reason(),
                "status",a.status(),"createdAt",a.createdAt(),"createdBy",a.createdBy(),"cancelledAt",a.cancelledAt(),
                "cancelledBy",a.cancelledBy(),"cancelReason",a.cancelReason(),"version",a.version())).toList());
        view.put("refunds",refunds.stream().map(r->fields("refundId",r.id(),"paymentId",r.paymentId(),"refundedOn",r.refundedOn(),
                "amount",r.amount(),"method",r.method(),"reason",r.reason(),"status",r.status(),"financialEntryId",r.entryId(),"createdAt",r.createdAt(),
                "createdBy",r.createdBy(),"cancelledAt",r.cancelledAt(),"cancelledBy",r.cancelledBy(),
                "cancelReason",r.cancelReason(),"version",r.version())).toList());
        return Map.copyOf(view);
    }
    private static Map<String,Object> summary(Billing b){return fields("billingId",b.id(),"yearMonth",b.yearMonth(),"baseAmount",b.baseAmount(),
            "confirmedAdjustmentAmount",b.adjustmentAmount(),"chargeAmount",b.charge(),"confirmedPaymentAmount",b.paymentAmount(),
            "confirmedRefundAmount",b.refundAmount(),"netPaidAmount",b.paymentAmount()-b.refundAmount(),"balance",b.balance(),
            "refundableAmount",b.refundable(),"paymentStatus",b.paymentStatus(),"displayStatus",displayStatus(b.balance(),b.dueDate()),"billingVersion",b.version());}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return java.util.Collections.unmodifiableMap(result);}
    private static String displayStatus(long balance,LocalDate dueDate){return balance<0?"CREDIT":balance==0?"PAID":dueDate.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))?"OVERDUE":balance>0?"PARTIALLY_PAID":"PAID";}
    private static String status(long balance,long paid){return balance<0?"CREDIT":balance==0?"PAID":paid==0?"ISSUED":"PARTIALLY_PAID";}
    private static void checkBillingVersion(Billing b,long expected){if(b.version()!=expected)throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_VERSION_CONFLICT");}
    private static void validateAdjustment(AdjustmentRequest r,UUID key){
        if(r==null||key==null||r.type()==null||!List.of("DISCOUNT","MATERIAL","EXTRA","CORRECTION").contains(r.type())||r.signedAmount()==0
                ||Math.abs(r.signedAmount())>999999999999L||r.billingVersion()<0||!validReason(r.reason(),5,300)
                ||"DISCOUNT".equals(r.type())&&r.signedAmount()>0||List.of("MATERIAL","EXTRA").contains(r.type())&&r.signedAmount()<0)
            throw new TuitionAdjustmentException("VALIDATION_ERROR");
    }
    private static void validatePreview(PreviewRequest r){if(r==null)throw new TuitionAdjustmentException("VALIDATION_ERROR");
        if(r.type()==null){if(r.signedAmount()!=null)throw new TuitionAdjustmentException("VALIDATION_ERROR");}
        else{if(!List.of("DISCOUNT","MATERIAL","EXTRA","CORRECTION").contains(r.type())||r.signedAmount()==null||r.signedAmount()==0
                ||Math.abs(r.signedAmount())>999999999999L||!validReason(r.reason(),5,300)||"DISCOUNT".equals(r.type())&&r.signedAmount()>0
                ||List.of("MATERIAL","EXTRA").contains(r.type())&&r.signedAmount()<0)throw new TuitionAdjustmentException("VALIDATION_ERROR");}
        if(r.refundAmount()!=null&&(r.refundAmount()<1||r.paymentId()==null&&r.paymentVersion()!=null||r.paymentId()!=null&&r.paymentVersion()==null))throw new TuitionAdjustmentException("VALIDATION_ERROR");}
    private static void validateRefund(RefundRequest r,UUID key){if(r==null||key==null||r.amount()<1||r.amount()>999999999999L||r.refundedOn()==null
            ||!List.of("CASH","TRANSFER","CARD","OTHER").contains(r.method())||!validReason(r.reason(),5,300)||r.billingVersion()<0
            ||r.paymentId()==null&&r.paymentVersion()!=null)throw new TuitionAdjustmentException("VALIDATION_ERROR");}
    private static void validateCancel(CancelRequest r,UUID key){if(r==null||key==null||!validReason(r.reason(),5,200)||r.targetVersion()<0||r.billingVersion()<0)throw new TuitionAdjustmentException("VALIDATION_ERROR");}
    private static boolean validReason(String s,int min,int max){return s!=null&&s.trim().length()>=min&&s.trim().length()<=max;}
    private static UUID actor(Authentication a){try{return UUID.fromString(a.getName());}catch(Exception e){throw new TuitionAdjustmentException("TUITION_ADJUSTMENT_WRITE_DENIED");}}
    private static void require(Authentication a,String p){if(a==null||a.getAuthorities().stream().noneMatch(v->p.equals(v.getAuthority())))throw new TuitionAdjustmentException(p.endsWith("READ")?"TUITION_ADJUSTMENT_READ_DENIED":p.equals("TUITION_REFUND_WRITE")?"TUITION_REFUND_WRITE_DENIED":"TUITION_ADJUSTMENT_WRITE_DENIED");}
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static AuditRecorder.Event event(Metadata m,UUID actor,String action,UUID billingId,String operation,Map<String,Object> details){return new AuditRecorder.Event(
            java.time.Instant.now(),m.requestId(),"MGT-TUITION-ADJUSTMENT","FINANCE","ADMIN",actor,null,action,"TUITION_BILLING",billingId,"SUCCESS",operation,m.ipAddress(),m.userAgent(),details);}
    public record AdjustmentRequest(String type,long signedAmount,String reason,long billingVersion){}
    public record PreviewRequest(String type,Long signedAmount,String reason,Long refundAmount,UUID paymentId,Long paymentVersion){}
    public record RefundRequest(long amount,LocalDate refundedOn,String method,UUID paymentId,Long paymentVersion,String reason,long billingVersion){}
    public record CancelRequest(String reason,long targetVersion,long billingVersion){}
    public record Metadata(String requestId,String ipAddress,String userAgent){}
}
