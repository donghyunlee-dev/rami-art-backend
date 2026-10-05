package com.ramiart.admin.finance.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.finance.application.FinancialEntryModels.*;
import com.ramiart.admin.finance.application.FinancialEntryService;
import com.ramiart.admin.finance.application.FinancialEntryService.FinancialEntryException;
import com.ramiart.admin.finance.application.FinancialEntryService.Metadata;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public final class FinancialEntryController {
    private static final Logger LOG = LoggerFactory.getLogger(FinancialEntryController.class);
    private final FinancialEntryService service;
    public FinancialEntryController(FinancialEntryService service) { this.service = service; }

    @GetMapping("/financial-entries")
    ResponseEntity<ApiEnvelope<EntryPage>> list(@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) List<String> types,
            @RequestParam(required=false) List<UUID> accountIds, @RequestParam(required=false) List<String> categoryCodes,
            @RequestParam(required=false) List<String> statuses, @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            Authentication auth, HttpServletRequest request) {
        return ok(service.list(from,to,types,accountIds,categoryCodes,statuses,keyword,page,size,auth),request);
    }
    @GetMapping("/finance-ledger-options")
    ResponseEntity<ApiEnvelope<Options>> options(Authentication auth,HttpServletRequest request) { return ok(service.options(auth),request); }
    @PostMapping("/financial-entries")
    ResponseEntity<ApiEnvelope<Entry>> create(@RequestHeader("Idempotency-Key") UUID key,@RequestBody CreateRequest body,
            Authentication auth,HttpServletRequest request) {
        WriteResult result=service.create(body,key,metadata(request),auth);
        return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.entry(),RequestIdFilter.get(request)));
    }
    @PostMapping("/financial-entries/{entryId}/cancellations")
    ResponseEntity<ApiEnvelope<Entry>> cancel(@PathVariable UUID entryId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CancelRequest body,Authentication auth,HttpServletRequest request) {
        WriteResult result=service.cancel(entryId,body,key,metadata(request),auth);
        return ResponseEntity.status(result.created()?HttpStatus.OK:HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.entry(),RequestIdFilter.get(request)));
    }
    private static Metadata metadata(HttpServletRequest r) {
        String agent=r.getHeader("User-Agent"); if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);
        return new Metadata(RequestIdFilter.get(r),r.getRemoteAddr(),agent);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(request)));
    }
    @ExceptionHandler(FinancialEntryException.class)
    ResponseEntity<ApiEnvelope<Void>> error(FinancialEntryException e,HttpServletRequest request) {
        String code=e.code(); HttpStatus status=switch(code) {
            case "FINANCE_READ_DENIED","FINANCE_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "FINANCIAL_ENTRY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FINANCIAL_ENTRY_ALREADY_CANCELLED","FINANCIAL_ENTRY_VERSION_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "FINANCIAL_ENTRY_LINKED_SOURCE","FINANCIAL_ENTRY_INVALID_AMOUNT","FINANCE_CATEGORY_TYPE_MISMATCH","FINANCE_ACCOUNT_INACTIVE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "FINANCIAL_ENTRY_SAVE_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,"원장 거래를 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException e,HttpServletRequest request) {
        LOG.error("Financial entry persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(request),request.getMethod(),request.getRequestURI());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.failure("FINANCIAL_ENTRY_SAVE_FAILED","원장 거래를 처리하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
