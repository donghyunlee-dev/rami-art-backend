package com.ramiart.admin.financeimport.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.financeimport.application.FinancialImportModels.*;
import com.ramiart.admin.financeimport.application.FinancialImportService;
import com.ramiart.admin.financeimport.application.FinancialImportService.FinancialImportException;
import com.ramiart.admin.financeimport.application.FinancialImportService.Metadata;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/financial-imports")
public final class FinancialImportController {
    private final FinancialImportService service;
    public FinancialImportController(FinancialImportService service){this.service=service;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiEnvelope<Batch>> upload(@RequestPart("file")MultipartFile file,@RequestParam UUID accountId,Authentication auth,HttpServletRequest request){
        Batch batch=service.upload(file,accountId,auth,metadata(request));return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(batch,RequestIdFilter.get(request)));
    }
    @GetMapping("/{batchId}")
    ResponseEntity<ApiEnvelope<Batch>> get(@PathVariable UUID batchId,Authentication auth,HttpServletRequest request){return ok(service.get(batchId,auth),request);}
    @GetMapping("/{batchId}/rows")
    ResponseEntity<ApiEnvelope<RowPage>> rows(@PathVariable UUID batchId,@RequestParam(required=false)List<String> statuses,@RequestParam(required=false)String cursor,
            @RequestParam(defaultValue="50")int size,Authentication auth,HttpServletRequest request){return ok(service.rows(batchId,statuses,cursor,size,auth),request);}
    @PostMapping("/{batchId}/confirmations")
    ResponseEntity<ApiEnvelope<Confirmation>> confirm(@PathVariable UUID batchId,@RequestHeader("Idempotency-Key")UUID key,
            @RequestBody ConfirmationRequest body,Authentication auth,HttpServletRequest request){return ok(service.confirm(batchId,body,key,auth,metadata(request)),request);}
    @GetMapping("/{batchId}/result-file")
    ResponseEntity<byte[]> result(@PathVariable UUID batchId,Authentication auth){
        byte[] bytes=service.resultFile(batchId,auth).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("financial-import-"+batchId+".csv").build().toString()).body(bytes);
    }
    private static Metadata metadata(HttpServletRequest r){String agent=r.getHeader("User-Agent");if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);return new Metadata(RequestIdFilter.get(r),r.getRemoteAddr(),agent);}
    private static <T>ResponseEntity<ApiEnvelope<T>>ok(T value,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(r)));}
    @ExceptionHandler(FinancialImportException.class)
    ResponseEntity<ApiEnvelope<Void>>error(FinancialImportException e,HttpServletRequest r){String code=e.code();HttpStatus status=switch(code){
        case "FINANCE_READ_DENIED","FINANCE_IMPORT_DENIED"->HttpStatus.FORBIDDEN;case "IMPORT_BATCH_NOT_FOUND"->HttpStatus.NOT_FOUND;
        case "IMPORT_BATCH_VERSION_CONFLICT","IMPORT_BATCH_NOT_CONFIRMABLE","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;
        case "FINANCE_ACCOUNT_INACTIVE","IMPORT_RESULT_NOT_READY","IMPORT_ROWS_INVALID"->HttpStatus.UNPROCESSABLE_ENTITY;
        case "IMPORT_SAVE_FAILED","IMPORT_PARSE_FAILED","IMPORT_CONFIRMATION_FAILED","IMPORT_STORAGE_UNAVAILABLE"->HttpStatus.INTERNAL_SERVER_ERROR;
        default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,"거래 가져오기를 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));}
}
