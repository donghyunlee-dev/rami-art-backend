package com.ramiart.admin.makeup.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.makeup.application.MakeupModels.CasePage;
import com.ramiart.admin.makeup.application.MakeupModels.CandidatePage;
import com.ramiart.admin.makeup.application.MakeupModels.Detail;
import com.ramiart.admin.makeup.application.MakeupService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.HttpHeaders;
import com.ramiart.admin.makeup.application.MakeupService.Metadata;
import com.ramiart.admin.makeup.application.MakeupModels.Reservation;
import com.ramiart.admin.makeup.application.MakeupModels.ReservationResult;
import com.ramiart.admin.makeup.application.MakeupModels.VersionedReason;
import com.ramiart.admin.makeup.application.MakeupModels.Extension;
import com.ramiart.admin.auth.api.AuthSessionController;
import jakarta.servlet.http.Cookie;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/makeups")
public class MakeupController {
    private static final Logger LOGGER=LoggerFactory.getLogger(MakeupController.class);
    private final MakeupService service;
    public MakeupController(MakeupService service) { this.service = service; }

    @GetMapping
    ResponseEntity<ApiEnvelope<CasePage>> list(@RequestParam(required=false) String status,
            @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to,
            @RequestParam(required=false) UUID studentId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, Authentication auth, HttpServletRequest request) {
        return ok(service.list(status, from, to, studentId, page, size, auth), request);
    }
    @GetMapping("/{id}") ResponseEntity<ApiEnvelope<Detail>> detail(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        return ok(service.detail(id, auth), request);
    }
    @GetMapping("/{id}/candidate-sessions") ResponseEntity<ApiEnvelope<CandidatePage>> candidates(@PathVariable UUID id,
            @RequestParam LocalDate from, @RequestParam LocalDate to, Authentication auth, HttpServletRequest request) {
        return ok(service.candidates(id, from, to, auth), request);
    }
    @PostMapping("/{id}/reservations")
    ResponseEntity<ApiEnvelope<Detail>> reserve(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Reservation body,Authentication auth,HttpServletRequest request) {
        ReservationResult result=service.reserve(id,body,key,auth,metadata(request));
        return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(
                result.detail(),RequestIdFilter.get(request)));
    }
    @PostMapping("/{id}/reservation-cancellation")
    ResponseEntity<ApiEnvelope<Detail>> cancel(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody VersionedReason body,Authentication auth,HttpServletRequest request) {
        return ok(service.cancel(id,body,key,auth,metadata(request)),request);
    }
    @PostMapping("/{id}/waiver")
    ResponseEntity<ApiEnvelope<Detail>> waive(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody VersionedReason body,Authentication auth,HttpServletRequest request) {
        return ok(service.waive(id,body,key,auth,metadata(request)),request);
    }
    @PostMapping("/{id}/extension")
    ResponseEntity<ApiEnvelope<Detail>> extend(@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody Extension body,Authentication auth,HttpServletRequest request) {
        String session=request.getCookies()==null?null:java.util.Arrays.stream(request.getCookies()).filter(cookie->AuthSessionController.COOKIE_NAME.equals(cookie.getName())).map(Cookie::getValue).findFirst().orElse(null);
        return ok(service.extend(id,body,key,session,auth,metadata(request)),request);
    }
    private static Metadata metadata(HttpServletRequest request) {
        String ua=request.getHeader(HttpHeaders.USER_AGENT);if(ua!=null)ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua!=null&&ua.length()>512)ua=ua.substring(0,512);
        return new Metadata(RequestIdFilter.get(request),request.getRemoteAddr(),ua);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }
    @ExceptionHandler(MakeupService.MakeupException.class)
    ResponseEntity<ApiEnvelope<Void>> error(MakeupService.MakeupException exception,HttpServletRequest request) {
        HttpStatus status=switch(exception.code()) {
            case "SESSION_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "MAKEUP_READ_DENIED","MAKEUP_WRITE_DENIED","ADMIN_ACCOUNT_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "REAUTHENTICATION_REQUIRED","REAUTHENTICATION_FAILED" -> HttpStatus.FORBIDDEN;
            case "MAKEUP_CASE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "MAKEUP_VERSION_CONFLICT","MAKEUP_CAPACITY_FULL","MAKEUP_SESSION_CLOSED","IDEMPOTENCY_KEY_REUSED" -> HttpStatus.CONFLICT;
            case "MAKEUP_SESSION_INCOMPATIBLE","MAKEUP_STUDENT_TIME_CONFLICT","MAKEUP_EXPIRED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
                ApiEnvelope.failure(exception.code(),"보강 정보를 처리할 수 없습니다.",java.util.List.of(),RequestIdFilter.get(request)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception,HttpServletRequest request) {
        LOGGER.error("Makeup persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(request),request.getMethod(),request.getRequestURI());
        String code="GET".equals(request.getMethod())?"MAKEUP_READ_FAILED":"MAKEUP_WRITE_FAILED";
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).body(
                ApiEnvelope.failure(code,"보강 정보를 처리하지 못했습니다.",java.util.List.of(),RequestIdFilter.get(request)));
    }
}
