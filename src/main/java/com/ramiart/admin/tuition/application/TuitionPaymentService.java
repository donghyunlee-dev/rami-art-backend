package com.ramiart.admin.tuition.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.tuition.application.TuitionPaymentRepository.Billing;
import com.ramiart.admin.tuition.application.TuitionPaymentRepository.Payment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class TuitionPaymentService {
    private final TuitionPaymentRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;
    public TuitionPaymentService(TuitionPaymentRepository repository,AuditRecorder audit,Clock clock) {
        this.repository=repository; this.audit=audit; this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(UUID billingId,String cursor,int requestedSize,Authentication auth) {
        require(auth,"TUITION_PAYMENT_READ");
        if(requestedSize<1||requestedSize>50) throw new TuitionPaymentException("VALIDATION_ERROR");
        Billing billing=repository.findBilling(billingId).orElseThrow(()->new TuitionPaymentException("TUITION_BILLING_NOT_FOUND"));
        Cursor decoded=decode(cursor);
        List<Payment> page=repository.list(billingId,decoded==null?null:decoded.createdAt(),decoded==null?null:decoded.id(),requestedSize+1);
        boolean hasNext=page.size()>requestedSize;
        List<Payment> values=hasNext?page.subList(0,requestedSize):page;
        String next=hasNext&&!values.isEmpty()?encode(values.getLast()):null;
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("billing",billingView(billing)); data.put("payments",values.stream().map(this::paymentView).toList());
        Map<String,Object> pageInfo=new LinkedHashMap<>();pageInfo.put("nextCursor",next);pageInfo.put("hasNext",hasNext);pageInfo.put("size",requestedSize);
        data.put("page",pageInfo);
        return java.util.Collections.unmodifiableMap(data);
    }
    @Transactional
    public Map<String,Object> create(UUID billingId,CreateRequest request,UUID key,Metadata metadata,Authentication auth) {
        require(auth,"TUITION_PAYMENT_WRITE"); validate(request,key);
        UUID actor=actor(auth); LocalDate today=LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        if(request.paidOn().isAfter(today)) throw new TuitionPaymentException("VALIDATION_ERROR");
        Billing billing=repository.lockBilling(billingId).orElseThrow(()->new TuitionPaymentException("TUITION_BILLING_NOT_FOUND"));
        String scope="MGT-TUITION-PAYMENT:"+actor; String hash=hash(billingId+"|"+request.paidOn()+"|"+request.amount()+"|"+request.method()+"|"+request.memo()+"|"+request.billingVersion());
        var claim=repository.claim(scope,key,hash);
        if(!claim.created()) return createdResponse(repository.find(claim.resourceId()).orElseThrow(()->new TuitionPaymentException("TUITION_PAYMENT_NOT_FOUND")),billing);
        if(billing.version()!=request.billingVersion()) throw new TuitionPaymentException("BILLING_VERSION_CONFLICT");
        if(billing.balance()<=0) throw new TuitionPaymentException("BILLING_NOT_PAYABLE");
        if(request.amount()>billing.balance()) throw new TuitionPaymentException("PAYMENT_EXCEEDS_BALANCE");
        UUID paymentId=UUID.randomUUID(),entryId=UUID.randomUUID();
        long paid=billing.paidAmount()+request.amount(); String status=paid>=billing.billedAmount()+billing.adjustmentAmount()+billing.refundedAmount()?"PAID":"PARTIALLY_PAID";
        repository.insert(billingId,paymentId,entryId,actor,request.paidOn(),request.amount(),request.method(),normalizeMemo(request.memo()),paid,status,billing.version());
        audit.record(event(metadata,actor,"TUITION_PAYMENT_CREATED",paymentId,"CREATE",Map.of("amount",request.amount(),"method",request.method())));
        repository.complete(scope,key,paymentId,201);
        return createdResponse(repository.find(paymentId).orElseThrow(),repository.lockBilling(billingId).orElseThrow());
    }
    @Transactional
    public Map<String,Object> cancel(UUID paymentId,CancelRequest request,UUID key,Metadata metadata,Authentication auth) {
        require(auth,"TUITION_PAYMENT_WRITE");
        if(request==null||key==null||request.reason()==null||request.reason().trim().length()<5||request.reason().trim().length()>200)
            throw new TuitionPaymentException("VALIDATION_ERROR");
        UUID actor=actor(auth); Payment payment=repository.lockPayment(paymentId).orElseThrow(()->new TuitionPaymentException("TUITION_PAYMENT_NOT_FOUND"));
        Billing billing=repository.lockBilling(payment.billingId()).orElseThrow(()->new TuitionPaymentException("TUITION_BILLING_NOT_FOUND"));
        String scope="MGT-TUITION-PAYMENT-CANCEL:"+actor; String reason=request.reason().trim();
        String hash=hash(paymentId+"|"+reason+"|"+request.paymentVersion()+"|"+request.billingVersion());
        var claim=repository.claim(scope,key,hash);
        if(!claim.created()) return cancellationResponse(repository.find(claim.resourceId()).orElseThrow(()->new TuitionPaymentException("TUITION_PAYMENT_NOT_FOUND")),billing,repository.findEntry(claim.resourceId()).orElse(null));
        if("CANCELLED".equals(payment.status())&&reason.equals(payment.cancelReason())) {
            repository.complete(scope,key,paymentId,200);
            return cancellationResponse(payment,billing,payment.entryId());
        }
        if(payment.version()!=request.paymentVersion()) throw new TuitionPaymentException("PAYMENT_VERSION_CONFLICT");
        if(billing.version()!=request.billingVersion()) throw new TuitionPaymentException("BILLING_VERSION_CONFLICT");
        if(!"CONFIRMED".equals(payment.status())) throw new TuitionPaymentException("PAYMENT_ALREADY_CANCELLED");
        long paid=billing.paidAmount()-payment.amount(); long balance=billing.billedAmount()+billing.adjustmentAmount()-paid+billing.refundedAmount();
        String state=balance<0?"CREDIT":balance==0?"PAID":paid==0?"ISSUED":"PARTIALLY_PAID";
        UUID entryId=payment.entryId()!=null?payment.entryId():repository.findEntry(paymentId).orElseThrow(()->new TuitionPaymentException("PAYMENT_SAVE_FAILED"));
        repository.cancel(paymentId,entryId,actor,reason,paid,state,request.paymentVersion(),request.billingVersion());
        audit.record(event(metadata,actor,"TUITION_PAYMENT_CANCELLED",paymentId,"CANCEL",Map.of("amount",payment.amount(),"reasonCode","ADMIN_CORRECTION")));
        repository.complete(scope,key,paymentId,200);
        return cancellationResponse(repository.find(paymentId).orElseThrow(),repository.lockBilling(payment.billingId()).orElseThrow(),entryId);
    }
    private Map<String,Object> createdResponse(Payment payment,Billing billing) {
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("entryId",payment.entryId());entry.put("sourceType","TUITION_PAYMENT");
        entry.put("type","INCOME");entry.put("categoryCode","TUITION");entry.put("amount",payment.amount());entry.put("status",payment.status());
        Map<String,Object> result=new LinkedHashMap<>();result.put("payment",paymentView(payment));result.put("financialEntry",entry);
        result.put("billing",billingSummary(billing,billing.paidAmount(),billing.paymentStatus()));return java.util.Collections.unmodifiableMap(result);
    }
    private Map<String,Object> cancellationResponse(Payment payment,Billing billing,UUID entryId) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("payment",paymentView(payment));result.put("financialEntryId",entryId);
        result.put("billing",billingSummary(billing,billing.paidAmount(),billing.paymentStatus()));return java.util.Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> billingSummary(Billing b,long paid,String status) {
        return Map.of("billingId",b.id(),"billedAmount",b.billedAmount()+b.adjustmentAmount(),"paidAmount",paid,
                "balance",b.billedAmount()+b.adjustmentAmount()-paid+b.refundedAmount(),"paymentStatus",status,"version",b.version());
    }
    private static Map<String,Object> billingView(Billing b) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("billingId",b.id());m.put("student",Map.of("studentId",b.studentId(),"name",b.studentName()));
        m.put("yearMonth",b.yearMonth());m.put("billedAmount",b.billedAmount()+b.adjustmentAmount());m.put("paidAmount",b.paidAmount());
        m.put("balance",b.balance());m.put("dueDate",b.dueDate());m.put("paymentStatus",b.paymentStatus());
        m.put("displayStatus",b.balance()>0&&b.dueDate().isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))?"OVERDUE":b.paymentStatus());
        m.put("version",b.version());return Map.copyOf(m);
    }
    private Map<String,Object> paymentView(Payment p) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("paymentId",p.id());m.put("paidOn",p.paidOn());m.put("amount",p.amount());m.put("method",p.method());
        m.put("memo",p.memo());m.put("status",p.status());m.put("createdAt",p.createdAt());m.put("createdBy",Map.of("adminUserId",p.createdBy(),"displayName",p.createdByName()));
        m.put("cancelledAt",p.cancelledAt());m.put("cancelledBy",p.cancelledBy()==null?null:Map.of("adminUserId",p.cancelledBy(),"displayName",p.cancelledByName()));
        m.put("cancelReason",p.cancelReason());m.put("version",p.version());m.put("refundedAmount",p.refundedAmount());m.put("refundableAmount",p.amount()-p.refundedAmount());m.put("financialEntryId",p.entryId());return java.util.Collections.unmodifiableMap(m);
    }
    private static AuditRecorder.Event event(Metadata m,UUID actor,String action,UUID target,String operation,Map<String,Object> details) {
        return new AuditRecorder.Event(java.time.Instant.now(),m.requestId(),"MGT-TUITION-PAYMENT-RECORD","FINANCE","ADMIN",actor,null,
                action,"TUITION_PAYMENT",target,"SUCCESS",operation,m.ipAddress(),m.userAgent(),details);
    }
    private static void validate(CreateRequest r,UUID key) {
        if(r==null||key==null||r.paidOn()==null||r.amount()<1||r.amount()>999999999999L||!List.of("CASH","TRANSFER","CARD","OTHER").contains(r.method())
                ||r.billingVersion()<0||r.memo()!=null&&r.memo().trim().length()>300) throw new TuitionPaymentException("VALIDATION_ERROR");
    }
    private static String normalizeMemo(String memo) { return memo==null||memo.trim().isEmpty()?null:memo.trim(); }
    private static UUID actor(Authentication a) { try{return UUID.fromString(a.getName());}catch(Exception e){throw new TuitionPaymentException("TUITION_PAYMENT_WRITE_DENIED");} }
    private static void require(Authentication a,String permission) { if(a==null||a.getAuthorities().stream().noneMatch(x->permission.equals(x.getAuthority())))throw new TuitionPaymentException(permission.endsWith("READ")?"TUITION_PAYMENT_READ_DENIED":"TUITION_PAYMENT_WRITE_DENIED"); }
    private static String hash(String s) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private static String encode(Payment p) { return Base64.getUrlEncoder().withoutPadding().encodeToString((p.createdAt()+"|"+p.id()).getBytes(StandardCharsets.UTF_8)); }
    private static Cursor decode(String cursor) { if(cursor==null||cursor.isBlank())return null;try{String[] p=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);if(p.length!=2)throw new IllegalArgumentException();return new Cursor(java.time.OffsetDateTime.parse(p[0]),UUID.fromString(p[1]));}catch(Exception e){throw new TuitionPaymentException("PAYMENT_CURSOR_INVALID");} }
    private record Cursor(java.time.OffsetDateTime createdAt,UUID id) {}
    public record CreateRequest(LocalDate paidOn,long amount,String method,String memo,long billingVersion) {}
    public record CancelRequest(String reason,long paymentVersion,long billingVersion) {}
    public record Metadata(String requestId,String ipAddress,String userAgent) {}
    public static final class TuitionPaymentException extends RuntimeException { private final String code; public TuitionPaymentException(String code){super(code);this.code=code;}public String code(){return code;} }
}
