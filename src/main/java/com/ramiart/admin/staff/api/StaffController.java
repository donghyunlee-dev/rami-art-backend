package com.ramiart.admin.staff.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.staff.application.StaffModels.AssignmentSetWrite;
import com.ramiart.admin.staff.application.StaffModels.StaffDetail;
import com.ramiart.admin.staff.application.StaffModels.StaffOptions;
import com.ramiart.admin.staff.application.StaffModels.StaffPage;
import com.ramiart.admin.staff.application.StaffModels.StaffWrite;
import com.ramiart.admin.staff.application.StaffService;
import com.ramiart.admin.staff.application.StaffService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/staff")
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<StaffPage>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jobTitle,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ok(service.findStaff(keyword, status, jobTitle, page, size), request);
    }

    @GetMapping("/options")
    ResponseEntity<ApiEnvelope<StaffOptions>> options(HttpServletRequest request) {
        return ok(service.findOptions(), request);
    }

    @GetMapping("/{staffId}")
    ResponseEntity<ApiEnvelope<StaffDetail>> detail(@PathVariable UUID staffId, HttpServletRequest request) {
        return ok(service.findStaff(staffId), request);
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<StaffDetail>> create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody StaffWrite body, Authentication authentication, HttpServletRequest request) {
        StaffDetail created = service.createStaff(body, actor(authentication), idempotencyKey, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(created, RequestIdFilter.get(request)));
    }

    @PutMapping("/{staffId}")
    ResponseEntity<ApiEnvelope<StaffDetail>> update(
            @PathVariable UUID staffId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody StaffWrite body, Authentication authentication, HttpServletRequest request) {
        return ok(service.updateStaff(staffId, body, actor(authentication), idempotencyKey, metadata(request)), request);
    }

    @PutMapping("/{staffId}/class-assignments")
    ResponseEntity<ApiEnvelope<StaffDetail>> replaceAssignments(
            @PathVariable UUID staffId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody AssignmentSetWrite body, Authentication authentication, HttpServletRequest request) {
        return ok(service.replaceAssignments(
                staffId, body, actor(authentication), idempotencyKey, metadata(request)), request);
    }

    private static UUID actor(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
        if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }
}
