package com.ramiart.admin.inquiry.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.inquiry.application.InquiryException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={PublicInquiryController.class,AdminInquiryController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class InquiryExceptionHandler {
    private static final Logger LOGGER=LoggerFactory.getLogger(InquiryExceptionHandler.class);
    @ExceptionHandler(InquiryException.class)
    ResponseEntity<ApiEnvelope<Void>> inquiry(InquiryException exception,HttpServletRequest request){return response(exception.code(),request);}

    @ExceptionHandler({DataAccessException.class,IllegalStateException.class})
    ResponseEntity<ApiEnvelope<Void>> persistence(Exception ignored,HttpServletRequest request){
        LOGGER.error("Inquiry persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(request),request.getMethod(),request.getRequestURI());
        return response("INQUIRY_SAVE_FAILED",request);
    }
    private static ResponseEntity<ApiEnvelope<Void>> response(String code,HttpServletRequest request){
        Definition d=definition(code); return ResponseEntity.status(d.status).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure(code,d.message,List.of(),RequestIdFilter.get(request)));
    }
    private static Definition definition(String code){return switch(code){
        case "INQUIRY_INVALID","INQUIRY_QUERY_INVALID"->new Definition(HttpStatus.BAD_REQUEST,"문의 입력값을 확인해 주세요.");
        case "PRIVACY_CONSENT_REQUIRED"->new Definition(HttpStatus.UNPROCESSABLE_ENTITY,"개인정보 수집 동의가 필요합니다.");
        case "CONSENT_POLICY_VERSION_INVALID"->new Definition(HttpStatus.UNPROCESSABLE_ENTITY,"최신 개인정보 동의문을 확인해 주세요.");
        case "INQUIRY_NOTE_REQUIRED"->new Definition(HttpStatus.UNPROCESSABLE_ENTITY,"처리 메모는 5자 이상 1000자 이하로 입력해 주세요.");
        case "INQUIRY_TRANSITION_DENIED"->new Definition(HttpStatus.UNPROCESSABLE_ENTITY,"허용되지 않은 문의 상태 변경입니다.");
        case "INQUIRY_NOT_FOUND"->new Definition(HttpStatus.NOT_FOUND,"문의를 찾을 수 없습니다.");
        case "INQUIRY_VERSION_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->new Definition(HttpStatus.CONFLICT,"문의가 변경되었습니다. 최신 내용을 다시 확인해 주세요.");
        case "INQUIRY_RATE_LIMITED"->new Definition(HttpStatus.TOO_MANY_REQUESTS,"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        default->new Definition(HttpStatus.INTERNAL_SERVER_ERROR,"문의 요청을 처리하지 못했습니다.");};}
    private record Definition(HttpStatus status,String message){}
}
