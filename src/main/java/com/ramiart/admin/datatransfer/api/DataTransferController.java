package com.ramiart.admin.datatransfer.api;

import com.ramiart.admin.datatransfer.application.DataTransferService;
import com.ramiart.admin.datatransfer.application.DataTransferModels.Job;
import com.ramiart.admin.datatransfer.application.DataTransferModels.RowPage;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/data-transfer")
public final class DataTransferController {
    private final DataTransferService service;
    public DataTransferController(DataTransferService service) { this.service = service; }

    @GetMapping("/templates/{domain}")
    ResponseEntity<byte[]> template(@PathVariable String domain, Authentication authentication) {
        DataTransferService.Template template = service.template(domain, authentication);
        byte[] body = template.csv().getBytes(StandardCharsets.UTF_8);
        String filename = template.version().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".csv";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Template-Version", template.version())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(body);
    }

    @GetMapping("/jobs/{id}")
    ResponseEntity<ApiEnvelope<Job>> job(@PathVariable java.util.UUID id, Authentication authentication,
            HttpServletRequest request) {
        return ok(service.job(id, authentication), request);
    }

    @GetMapping("/jobs/{id}/rows")
    ResponseEntity<ApiEnvelope<RowPage>> rows(@PathVariable java.util.UUID id,
            @RequestParam(required = false) java.util.List<String> status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication, HttpServletRequest request) {
        return ok(service.rows(id, status, cursor, size, authentication), request);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }
}
