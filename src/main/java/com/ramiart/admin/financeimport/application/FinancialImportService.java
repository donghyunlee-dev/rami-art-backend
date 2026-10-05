package com.ramiart.admin.financeimport.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.financeimport.application.FinancialImportModels.*;
import com.ramiart.admin.financeimport.application.FinancialImportRepository.Claim;
import com.ramiart.admin.financeimport.infrastructure.FinancialImportParserWorker;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FinancialImportService {
    private static final ZoneId STUDIO_ZONE=ZoneId.of("Asia/Seoul");
    private static final List<String> ALL_STATUSES=List.of("VALID","ERROR","DUPLICATE","IMPORTED","FAILED","EXCLUDED");
    private final FinancialImportRepository repository; private final FinancialImportStorage storage;
    private final AuditRecorder audit; private final FinancialImportParserWorker parserWorker;
    private final FinancialImportRowImporter rowImporter; private final Clock clock; private final String hmacKey;
    private final TransactionTemplate transaction;
    public FinancialImportService(FinancialImportRepository repository,FinancialImportStorage storage,AuditRecorder audit,
            @Lazy FinancialImportParserWorker parserWorker,FinancialImportRowImporter rowImporter,Clock clock,PlatformTransactionManager manager,
            @Value("${app.storage.service-key:}")String hmacKey){
        this.repository=repository;this.storage=storage;this.audit=audit;this.parserWorker=parserWorker;this.rowImporter=rowImporter;this.clock=clock;this.hmacKey=hmacKey;
        this.transaction=new TransactionTemplate(manager);
    }
    public Batch upload(MultipartFile file,UUID accountId,Authentication auth,Metadata metadata){
        UUID actor=require(auth,"FINANCE_IMPORT");
        if(file==null||file.isEmpty()||accountId==null||file.getSize()>5L*1024*1024)throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");
        if(!repository.activeAccount(accountId))throw new FinancialImportException("FINANCE_ACCOUNT_INACTIVE");
        String name=safeFileName(file.getOriginalFilename());byte[] bytes;
        try{bytes=file.getBytes();}catch(Exception e){throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");}
        UUID id=repository.create(name,sha256(bytes),accountId,actor);String key="financial-imports/"+id+".csv";
        repository.setStorageKey(id,key);
        try{storage.upload(key,bytes);}catch(RuntimeException e){try{storage.delete(key);}catch(RuntimeException ignored){}repository.failParsing(id);throw new FinancialImportException("IMPORT_STORAGE_UNAVAILABLE");}
        Batch batch=repository.batch(id).orElseThrow(()->new FinancialImportException("IMPORT_SAVE_FAILED"));
        audit.record(event(actor,metadata,"FINANCIAL_IMPORT_CREATED",id,Map.of("fileSize",bytes.length)));
        try{parserWorker.parse(id);}catch(RuntimeException e){repository.failParsing(id);throw new FinancialImportException("IMPORT_PARSE_FAILED");}
        return batch;
    }
    @Transactional public void parse(UUID batchId){
            String key=repository.storageKey(batchId).orElseThrow();List<List<String>> parsed=FinancialImportCsvParser.parse(decode(storage.download(key)));
            if(parsed.size()<2||parsed.size()>5001)throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");
            List<String> header=parsed.getFirst().stream().map(String::trim).toList();Set<String> allowed=Set.of("transactionDate","type","amount","description","categoryCode","externalId");
            if(new HashSet<>(header).size()!=header.size()||!header.containsAll(List.of("transactionDate","type","amount","description","categoryCode"))||header.stream().anyMatch(x->!allowed.contains(x)))throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");
            Batch batch=repository.batch(batchId).orElseThrow(()->new FinancialImportException("IMPORT_BATCH_NOT_FOUND"));List<RowInput> rows=new ArrayList<>();
            Map<String,UUID> seenExternal=new java.util.HashMap<>(),seenHashes=new java.util.HashMap<>();
            for(int index=1;index<parsed.size();index++){
                List<String> fields=parsed.get(index);int rowNumber=index+1;UUID rowId=UUID.randomUUID();List<Issue> issues=new ArrayList<>();
                if(fields.size()!=header.size()){rows.add(new RowInput(rowId,rowNumber,null,null,null,null,null,null,null,"ERROR",List.of(new Issue("row","CSV_COLUMN_COUNT_INVALID")),null,null));continue;}
                Map<String,String> values=new java.util.HashMap<>();for(int col=0;col<header.size();col++)values.put(header.get(col),fields.get(col));
                LocalDate date=null;try{date=LocalDate.parse(value(values,"transactionDate"));if(date.isAfter(LocalDate.now(clock.withZone(STUDIO_ZONE)))){issues.add(new Issue("transactionDate","TRANSACTION_DATE_FUTURE"));date=null;}}catch(Exception e){issues.add(new Issue("transactionDate","TRANSACTION_DATE_INVALID"));}
                String type=value(values,"type");if(!List.of("INCOME","EXPENSE").contains(type)){issues.add(new Issue("type","TRANSACTION_TYPE_INVALID"));type=null;}
                Long amount=null;try{String raw=value(values,"amount");if(!raw.matches("[0-9]{1,14}"))throw new NumberFormatException();long n=Long.parseLong(raw);if(n<1||n>99999999999999L)throw new NumberFormatException();amount=n;}catch(Exception e){issues.add(new Issue("amount","AMOUNT_INVALID"));}
                String description=value(values,"description").trim();if(description.isEmpty()||description.length()>200){issues.add(new Issue("description","DESCRIPTION_INVALID"));description=null;}
                String category=value(values,"categoryCode").trim();if(category.isEmpty()){issues.add(new Issue("categoryCode","CATEGORY_REQUIRED"));category=null;}else if(type==null||!repository.activeCategory(category,type)){issues.add(new Issue("categoryCode","CATEGORY_INVALID"));category=null;}
                String external=value(values,"externalId").trim();if(external.isEmpty())external=null;else if(external.length()>100){issues.add(new Issue("externalId","EXTERNAL_ID_INVALID"));external=null;}
                if(!issues.isEmpty()){rows.add(new RowInput(rowId,rowNumber,date,type,amount,description,category,external,null,"ERROR",issues,null,null));continue;}
                String dedup=external==null?hmac(date+"|"+type+"|"+amount+"|"+description):null;
                DuplicateEntry existing=repository.duplicateEntry(batch.account().accountId(),external,dedup).orElse(null);String local=external==null?dedup:external;
                UUID duplicateRow=existing==null?(external==null?seenHashes.get(local):seenExternal.get(local)):null;
                boolean duplicate=existing!=null||duplicateRow!=null;if(duplicate)issues.add(new Issue("row","IMPORT_DUPLICATE_DETECTED"));
                if(external==null)seenHashes.putIfAbsent(local,rowId);else seenExternal.putIfAbsent(local,rowId);
                rows.add(new RowInput(rowId,rowNumber,date,type,amount,description,category,external,dedup,duplicate?"DUPLICATE":"VALID",issues,existing==null?null:existing.entryId(),duplicateRow));
            }
            repository.parseRows(batchId,rows);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markParseFailed(UUID batchId){repository.failParsing(batchId);}
    public Batch get(UUID id,Authentication auth){require(auth,"FINANCE_READ");return repository.batch(id).orElseThrow(()->new FinancialImportException("IMPORT_BATCH_NOT_FOUND"));}
    public RowPage rows(UUID id,List<String> statuses,String cursor,int size,Authentication auth){
        require(auth,"FINANCE_READ");get(id,auth);if(size<20||size>200)throw new FinancialImportException("VALIDATION_ERROR");
        List<String> selected=statuses==null?ALL_STATUSES:statuses.stream().distinct().sorted().toList();if(selected.stream().anyMatch(x->!ALL_STATUSES.contains(x)))throw new FinancialImportException("VALIDATION_ERROR");
        String number=null,rowId=null;if(cursor!=null&&!cursor.isBlank()){String[] c=decodeCursor(cursor);number=c[0];rowId=c[1];}
        RowPage raw=repository.rows(id,selected,size,number,rowId);String next=raw.page().nextCursor()==null?null:encodeCursor(raw.page().nextCursor());
        return new RowPage(raw.items(),new RowPageInfo(size,next,raw.page().hasNext()));
    }
    public Confirmation confirm(UUID batchId,ConfirmationRequest request,UUID key,Authentication auth,Metadata metadata){
        UUID actor=require(auth,"FINANCE_IMPORT");if(request==null||request.rowIds()==null||request.rowIds().isEmpty()||request.rowIds().size()>5000||key==null||request.batchVersion()<0||new HashSet<>(request.rowIds()).size()!=request.rowIds().size())throw new FinancialImportException("VALIDATION_ERROR");
        String scope="MGT-FINANCE-IMPORT:"+actor;String hash=sha256((batchId+"|"+request.batchVersion()+"|"+request.rowIds().stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));
        Claim claim;try{claim=transaction.execute(s->{Claim c=repository.claim(batchId,request.batchVersion(),scope,key,hash);if(c.replay())return c;
            List<Row> valid=repository.selectedRows(batchId,request.rowIds());if(valid.size()!=request.rowIds().size())throw new FinancialImportException("IMPORT_ROWS_INVALID");
            repository.prepareConfirmation(batchId,request.rowIds().size(),request.batchVersion(),scope,key,hash);repository.excludeRows(repository.validRowsExcept(batchId,request.rowIds()));return c;});}
        catch(FinancialImportException e){throw e;}catch(IllegalStateException e){throw new FinancialImportException(e.getMessage());}
        if(claim==null)throw new FinancialImportException("IMPORT_CONFIRMATION_FAILED");
        if(claim.replay()&&!("CONFIRMING".equals(claim.batch().status())))return repository.confirmation(batchId);
        List<UUID> pending=repository.validRowsForConfirmation(batchId,5000);
        for(UUID rowId:pending){try{rowImporter.importRow(batchId,rowId,actor,metadata);}catch(DataAccessException e){repository.failRow(rowId,"IMPORT_DUPLICATE_DETECTED");}catch(RuntimeException e){repository.failRow(rowId,"IMPORT_ROW_FAILED");}}
        return transaction.execute(s->repository.finishConfirmation(batchId));
    }
    public String resultFile(UUID id,Authentication auth){Batch b=get(id,auth);if(!b.actions().canDownloadResult())throw new FinancialImportException("IMPORT_RESULT_NOT_READY");return "\uFEFF"+repository.resultCsv(id);}
    public void recoverConfirmations(){
        for(UUID batchId:repository.confirmingBatches(20)){
            UUID actor=repository.confirmationActor(batchId).orElse(null);if(actor==null)continue;
            Metadata metadata=new Metadata("financial-import-recovery-"+UUID.randomUUID(),null,"financial-import-recovery");
            for(UUID rowId:repository.validRowsForConfirmation(batchId,5000)){
                try{rowImporter.importRow(batchId,rowId,actor,metadata);}catch(DataAccessException e){repository.failRow(rowId,"IMPORT_DUPLICATE_DETECTED");}catch(RuntimeException e){repository.failRow(rowId,"IMPORT_ROW_FAILED");}
            }
            transaction.execute(status->repository.finishConfirmation(batchId));
        }
    }
    private static String value(Map<String,String> values,String key){return values.getOrDefault(key,"").trim();}
    private static String decode(byte[] bytes){try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();return text.startsWith("\uFEFF")?text.substring(1):text;}catch(Exception e){throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");}}
    private String hmac(String value){try{if(hmacKey==null||hmacKey.isBlank())throw new IllegalStateException();Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update("financial-import-dedup:v1:".getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new FinancialImportException("IMPORT_CONFIGURATION_INVALID");}}
    private String encodeCursor(String value){String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));return payload+"."+hmac("cursor:"+payload);}
    private String[] decodeCursor(String value){try{String[] p=value.split("\\.",-1);if(p.length!=2||!MessageDigest.isEqual(hmac("cursor:"+p[0]).getBytes(StandardCharsets.US_ASCII),p[1].getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException();String[] c=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8).split("\\|",-1);if(c.length!=2||Integer.parseInt(c[0])<2)throw new IllegalArgumentException();UUID.fromString(c[1]);return c;}catch(Exception e){throw new FinancialImportException("IMPORT_CURSOR_INVALID");}}
    private static String safeFileName(String original){if(original==null)throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");String cleaned=original.replace('\\','/');String name=Paths.get(cleaned).getFileName().toString().replaceAll("[\\p{Cntrl}]","").trim();if(name.isEmpty()||name.length()>255||!name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv"))throw new FinancialImportException("IMPORT_FILE_FORMAT_INVALID");return name;}
    private static String sha256(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
    private static UUID require(Authentication a,String permission){if(a==null||a.getAuthorities().stream().noneMatch(x->permission.equals(x.getAuthority())))throw new FinancialImportException(permission+"_DENIED");try{return UUID.fromString(a.getName());}catch(Exception e){throw new FinancialImportException(permission+"_DENIED");}}
    private AuditRecorder.Event event(UUID actor,Metadata m,String action,UUID id,Map<String,Object> details){return new AuditRecorder.Event(clock.instant(),m.requestId(),"MGT-FINANCE-IMPORT","FINANCE","ADMIN",actor,null,action,"FINANCIAL_IMPORT_BATCH",id,"SUCCESS",action,m.ipAddress(),m.userAgent(),details);}
    public record Metadata(String requestId,String ipAddress,String userAgent){}
    public static final class FinancialImportException extends RuntimeException{private final String code;public FinancialImportException(String code){super(code);this.code=code;}public String code(){return code;}}
}
