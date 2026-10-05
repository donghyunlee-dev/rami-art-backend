package com.ramiart.admin.finance.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.finance.application.FinanceSettlementModels.Options;
import com.ramiart.admin.finance.application.FinanceSettlementModels.Settlement;
import com.ramiart.admin.finance.application.FinanceSettlementService;
import com.ramiart.admin.finance.application.FinanceSettlementService.FinanceSettlementException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/finance-settlements")
public final class FinanceSettlementController {
    private final FinanceSettlementService service;
    public FinanceSettlementController(FinanceSettlementService service){this.service=service;}
    @GetMapping
    ResponseEntity<ApiEnvelope<Settlement>> get(@RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to,
            @RequestParam(required=false)List<UUID> accountIds,Authentication auth,HttpServletRequest request){
        return ok(service.get(from,to,accountIds,auth),request);
    }
    @GetMapping("/options")
    ResponseEntity<ApiEnvelope<Options>> options(Authentication auth,HttpServletRequest request){return ok(service.options(auth),request);}
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(request)));
    }
    @ExceptionHandler(FinanceSettlementException.class)
    ResponseEntity<ApiEnvelope<Void>> error(FinanceSettlementException e,HttpServletRequest request){
        HttpStatus status=switch(e.code()){case "FINANCE_READ_DENIED"->HttpStatus.FORBIDDEN;case "SETTLEMENT_QUERY_FAILED"->HttpStatus.INTERNAL_SERVER_ERROR;default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(e.code(),"재무 정산을 조회하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException e,HttpServletRequest request){
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure("SETTLEMENT_QUERY_FAILED","재무 정산을 조회하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
