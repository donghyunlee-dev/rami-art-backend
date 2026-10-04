package com.ramiart.admin.tuition.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.tuition.application.TuitionReceiptRepository.Receipt;
import com.ramiart.admin.tuition.application.TuitionReceiptRepository.Source;
import com.ramiart.admin.tuition.application.TuitionReceiptRepository.Version;
import com.ramiart.admin.tuition.infrastructure.JdbcTuitionReceiptRepository.TuitionReceiptException;
import com.ramiart.admin.tuition.infrastructure.TuitionReceiptPdfGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class TuitionReceiptService {
    private final TuitionReceiptRepository repository;
    private final TuitionReceiptStorage storage;
    private final TuitionReceiptPdfGenerator pdf;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public TuitionReceiptService(TuitionReceiptRepository repository,TuitionReceiptStorage storage,TuitionReceiptPdfGenerator pdf,
            AuditRecorder audit,Clock clock,PlatformTransactionManager transactionManager){
        this.repository=repository;this.storage=storage;this.pdf=pdf;this.audit=audit;this.clock=clock;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    public Map<String,Object> getByPayment(UUID paymentId,Authentication auth){
        require(auth,"TUITION_RECEIPT_READ");Source source=repository.source(paymentId).orElseThrow(()->new TuitionReceiptException("TUITION_PAYMENT_NOT_FOUND"));
        Receipt receipt=repository.byPayment(paymentId).orElse(null);
        Map<String,Object> response=receipt==null?fields("receipt",null,"paymentSummary",paymentSummary(source),"canIssue","CONFIRMED".equals(source.paymentStatus())):summary(receipt,source);
        return immutable(response);
    }
    public Map<String,Object> issue(UUID paymentId,IssueRequest request,UUID key,Metadata metadata,Authentication auth){
        require(auth,"TUITION_RECEIPT_ISSUE");UUID actor=actor(auth);validateIssue(request,key);
        String scope=actor+":POST:/admin/tuition/payments/{paymentId}/receipt";
        String requestHash=hash("issue|"+paymentId+"|"+request.paymentVersion()+"|"+request.billingVersion());
        IssuePlan plan=transactions.execute(status->{
            var claim=repository.claim(scope,key,requestHash);
            if(!claim.created())return existingPlan(claim.resourceId(),paymentId);
            Source source=repository.lockSource(paymentId).orElseThrow(()->new TuitionReceiptException("TUITION_PAYMENT_NOT_FOUND"));
            requireConfirmed(source);checkVersions(source,request.paymentVersion(),request.billingVersion());
            Receipt previous=repository.byPayment(paymentId).orElse(null);
            if(previous!=null){repository.complete(scope,key,previous.id(),200);return new IssuePlan(previous,null,null,null,false);}
            UUID receiptId=UUID.randomUUID(),versionId=UUID.randomUUID();String number=repository.nextReceiptNumber(java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneId.of("Asia/Seoul"))).toString());
            Map<String,Object> snapshot=snapshot(source,clock.instant());
            repository.create(receiptId,paymentId,number,actor,versionId,1,snapshot,source.refundAmount(),null);
            Receipt actual=repository.byPayment(paymentId).orElseThrow(()->new TuitionReceiptException("RECEIPT_GENERATION_FAILED"));
            if(!actual.id().equals(receiptId)){repository.complete(scope,key,actual.id(),200);return new IssuePlan(actual,null,null,null,false);}
            return new IssuePlan(actual,source,repository.version(receiptId,1).orElseThrow().snapshot(),versionId,true);
        });
        return plan.generate()?generate(plan,scope,key,actor,metadata,201):summary(plan.receipt(),plan.source()==null?repository.source(paymentId).orElseThrow():plan.source());
    }
    public Map<String,Object> reissue(UUID receiptId,ReissueRequest request,UUID key,Metadata metadata,Authentication auth){
        require(auth,"TUITION_RECEIPT_ISSUE");UUID actor=actor(auth);validateReissue(request,key);
        String scope=actor+":POST:/admin/tuition/receipts/{receiptId}/versions";
        String requestHash=hash("reissue|"+receiptId+"|"+request.receiptCurrentVersion()+"|"+request.issueReason().trim());
        IssuePlan plan=transactions.execute(status->{
            var claim=repository.claim(scope,key,requestHash);
            if(!claim.created())return existingPlan(claim.resourceId(),null);
            Receipt receipt=repository.lockReceipt(receiptId).orElseThrow(()->new TuitionReceiptException("RECEIPT_NOT_FOUND"));
            if(receipt.currentVersion()!=request.receiptCurrentVersion())throw new TuitionReceiptException("RECEIPT_VERSION_CONFLICT");
            if(!"ACTIVE".equals(receipt.status()))throw new TuitionReceiptException("RECEIPT_PAYMENT_NOT_CONFIRMED");
            Source source=repository.lockSource(receipt.paymentId()).orElseThrow(()->new TuitionReceiptException("TUITION_PAYMENT_NOT_FOUND"));
            requireConfirmed(source);int version=receipt.currentVersion()+1;UUID versionId=UUID.randomUUID();Map<String,Object> snapshot=snapshot(source,clock.instant());
            repository.beginReissue(receipt.id(),receipt.currentVersion(),version,versionId,actor,snapshot,source.refundAmount(),request.issueReason().trim());
            return new IssuePlan(new Receipt(receipt.id(),receipt.paymentId(),receipt.receiptNumber(),version,receipt.status()),source,snapshot,versionId,true);
        });
        return plan.generate()?generate(plan,scope,key,actor,metadata,201):summary(plan.receipt(),plan.source()==null?repository.source(plan.receipt().paymentId()).orElseThrow():plan.source());
    }
    public Map<String,Object> downloadUrl(UUID receiptId,int version,Metadata metadata,Authentication auth){
        require(auth,"TUITION_RECEIPT_READ");
        if(version<1)throw new TuitionReceiptException("VALIDATION_ERROR");
        Receipt receipt=repository.lockReceipt(receiptId).orElseThrow(()->new TuitionReceiptException("RECEIPT_NOT_FOUND"));
        Version file=repository.version(receiptId,version).orElseThrow(()->new TuitionReceiptException("RECEIPT_VERSION_NOT_FOUND"));
        if(!"READY".equals(file.status())||file.storageKey()==null||file.sha256()==null)throw new TuitionReceiptException("RECEIPT_GENERATION_IN_PROGRESS");
        byte[] bytes=storage.download(file.storageKey());String actual=sha256(bytes);
        if(!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),file.sha256().trim().getBytes(java.nio.charset.StandardCharsets.US_ASCII))){
            audit.record(event(metadata,actor(auth),"TUITION_RECEIPT_INTEGRITY_FAILED",receiptId,"DOWNLOAD","FAILURE",Map.of("version",version)));
            throw new TuitionReceiptException("RECEIPT_FILE_INTEGRITY_FAILED");
        }
        Instant expires=clock.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        return immutable(fields("url",storage.signedUrl(file.storageKey(),60),"expiresAt",expires,"sha256",file.sha256().trim(),"fileName",receipt.receiptNumber()+"-v"+version+".pdf"));
    }
    private Map<String,Object> generate(IssuePlan plan,String scope,UUID key,UUID actor,Metadata metadata,int status){
        String path="tuition-receipts/"+plan.receipt().receiptNumber().substring(2,8)+"/"+plan.receipt().id()+"-v"+plan.receipt().currentVersion()+".pdf";
        byte[] bytes;
        try{bytes=pdf.generate(plan.receipt().receiptNumber(),plan.receipt().currentVersion(),plan.source(),plan.snapshot());storage.upload(path,bytes);}
        catch(RuntimeException e){transactions.executeWithoutResult(tx->{repository.markFailed(plan.versionId());repository.complete(scope,key,plan.receipt().id(),status);});
            throw e instanceof TuitionReceiptException receiptException?receiptException:new TuitionReceiptException("RECEIPT_GENERATION_FAILED",e);}
        String hash=sha256(bytes);
        try{transactions.executeWithoutResult(tx->{repository.markReady(plan.versionId(),path,hash,bytes.length);repository.complete(scope,key,plan.receipt().id(),status);
            audit.record(event(metadata,actor,plan.receipt().currentVersion()==1?"TUITION_RECEIPT_ISSUED":"TUITION_RECEIPT_REISSUED",plan.receipt().id(),"ISSUE",
                    Map.of("receiptNumber",plan.receipt().receiptNumber(),"version",plan.receipt().currentVersion(),"fileSize",bytes.length)));});}
        catch(RuntimeException e){storage.delete(path);throw e;}
        return summary(plan.receipt(),plan.source());
    }
    private IssuePlan existingPlan(UUID resource,UUID paymentId){
        Receipt receipt=resource==null?null:repository.lockReceipt(resource).orElse(null);
        if(receipt==null&&paymentId!=null)receipt=repository.byPayment(paymentId).orElse(null);
        if(receipt==null)throw new TuitionReceiptException("RECEIPT_GENERATION_IN_PROGRESS");
        return new IssuePlan(receipt,null,null,null,false);
    }
    private Map<String,Object> summary(Receipt receipt,Source source){
        return fields("receiptId",receipt.id(),"receiptNumber",receipt.receiptNumber(),"status",receipt.status(),
                "currentVersion",receipt.currentVersion(),"paymentSummary",paymentSummary(source),"refundAmount",source.refundAmount(),
                "versions",repository.versions(receipt.id()).stream().map(v->fields("version",v.version(),"status",v.status(),"issuedAt",v.issuedAt(),
                        "issuedBy",v.issuedBy(),"issueReason",v.issueReason(),"fileSize",v.fileSize(),"downloadable","READY".equals(v.status()))).toList());
    }
    private static Map<String,Object> paymentSummary(Source s){return fields("paymentId",s.paymentId(),"billingId",s.billingId(),"yearMonth",s.yearMonth(),
            "paidOn",s.paidOn(),"amount",s.amount(),"method",s.method(),"paymentStatus",s.paymentStatus(),"paymentVersion",s.paymentVersion(),"billingVersion",s.billingVersion());}
    private static Map<String,Object> snapshot(Source s,Instant now){
        long net=Math.max(s.amount()-s.refundAmount(),0);
        return fields("schemaVersion",1,"studioName",s.studioName(),"maskedStudentName",mask(s.studentName()),"yearMonth",s.yearMonth(),
                "paidOn",s.paidOn(),"amount",s.amount(),"method",s.method(),"paymentStatus",s.paymentStatus(),"refundAmount",s.refundAmount(),
                "netPaidAmount",net,"issuedAt",now.truncatedTo(ChronoUnit.MICROS).toString());
    }
    private static String mask(String name){if(name==null||name.isBlank())return "원생";return name.substring(0,1)+"*".repeat(Math.max(2,name.codePointCount(0,name.length())-1));}
    private static void requireConfirmed(Source s){if(!"CONFIRMED".equals(s.paymentStatus()))throw new TuitionReceiptException("RECEIPT_PAYMENT_NOT_CONFIRMED");}
    private static void checkVersions(Source s,long payment,long billing){if(s.paymentVersion()!=payment||s.billingVersion()!=billing)throw new TuitionReceiptException("RECEIPT_VERSION_CONFLICT");}
    private static void validateIssue(IssueRequest r,UUID key){if(r==null||key==null||r.paymentVersion()==null||r.billingVersion()==null||r.paymentVersion()<0||r.billingVersion()<0)throw new TuitionReceiptException("VALIDATION_ERROR");}
    private static void validateReissue(ReissueRequest r,UUID key){if(r==null||key==null||r.receiptCurrentVersion()<1||r.issueReason()==null||r.issueReason().trim().length()<5||r.issueReason().trim().length()>200)throw new TuitionReceiptException("VALIDATION_ERROR");}
    private static UUID actor(Authentication auth){try{return UUID.fromString(auth.getName());}catch(Exception e){throw new TuitionReceiptException("TUITION_RECEIPT_ISSUE_DENIED");}}
    private static void require(Authentication auth,String permission){if(auth==null||auth.getAuthorities().stream().noneMatch(a->permission.equals(a.getAuthority())))throw new TuitionReceiptException(permission.equals("TUITION_RECEIPT_READ")?"TUITION_RECEIPT_READ_DENIED":"TUITION_RECEIPT_ISSUE_DENIED");}
    private static AuditRecorder.Event event(Metadata m,UUID actor,String action,UUID target,String operation,Map<String,Object> details){return event(m,actor,action,target,operation,"SUCCESS",details);}
    private static AuditRecorder.Event event(Metadata m,UUID actor,String action,UUID target,String operation,String result,Map<String,Object> details){return new AuditRecorder.Event(Instant.now(),m.requestId(),"MGT-TUITION-RECEIPT","FINANCE","ADMIN",actor,null,action,"TUITION_RECEIPT",target,result,operation,m.ipAddress(),m.userAgent(),details);}
    private static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static Map<String,Object> immutable(Map<String,Object> value){return java.util.Collections.unmodifiableMap(value);}
    private record IssuePlan(Receipt receipt,Source source,Map<String,Object> snapshot,UUID versionId,boolean generate){}
    public record IssueRequest(Long paymentVersion,Long billingVersion){}
    public record ReissueRequest(int receiptCurrentVersion,String issueReason){}
    public record Metadata(String requestId,String ipAddress,String userAgent){}
}
