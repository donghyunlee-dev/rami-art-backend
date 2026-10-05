package com.ramiart.admin.datatransfer.api;

import com.ramiart.admin.datatransfer.application.DataTransferService;
import com.ramiart.admin.datatransfer.application.DataTransferModels.Job;
import com.ramiart.admin.datatransfer.application.DataTransferModels.RowPage;
import com.ramiart.admin.datatransfer.application.DataTransferModels.ConfirmResponse;
import com.ramiart.admin.datatransfer.application.DataTransferService.ExportRequest;
import com.ramiart.admin.datatransfer.application.DataTransferService.ConfirmRequest;
import com.ramiart.admin.datatransfer.application.DataTransferService.RequestMetadata;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/data-transfer")
public final class DataTransferController {
    private final DataTransferService service;
    public DataTransferController(DataTransferService service) { this.service = service; }

    @PostMapping("/imports")
    ResponseEntity<ApiEnvelope<Job>> upload(@RequestPart("domain") String domain,
            @RequestPart("templateVersion") String templateVersion,
            @RequestPart("file") MultipartFile file, Authentication authentication, HttpServletRequest request) {
        Job job = service.upload(domain, templateVersion, file, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.LOCATION, "/api/admin/data-transfer/jobs/" + job.id())
                .body(ApiEnvelope.success(job, RequestIdFilter.get(request)));
    }

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

    @PostMapping("/jobs/{id}/confirm")
    ResponseEntity<ApiEnvelope<ConfirmResponse>> confirm(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody ConfirmRequest body,
            Authentication authentication, HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
        RequestMetadata metadata = new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(),
                userAgent == null ? null : userAgent.substring(0, Math.min(512, userAgent.length())));
        return ok(service.confirm(id, body, key, metadata, authentication), request);
    }

    @PostMapping("/exports/preview")
    ResponseEntity<ApiEnvelope<DataTransferService.ExportPreview>> previewExport(@RequestBody ExportRequest body,
            Authentication authentication, HttpServletRequest request) {
        return ok(service.previewExport(body, authentication), request);
    }

    @PostMapping("/exports")
    ResponseEntity<ApiEnvelope<Job>> createExport(@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ExportRequest body, Authentication authentication, HttpServletRequest request) {
        Job job = service.createExport(body, key, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.LOCATION, "/api/admin/data-transfer/jobs/" + job.id())
                .body(ApiEnvelope.success(job, RequestIdFilter.get(request)));
    }

    @GetMapping("/jobs/{id}/download-url")
    ResponseEntity<ApiEnvelope<DataTransferService.DownloadUrl>> downloadUrl(@PathVariable UUID id,
            Authentication authentication, HttpServletRequest request) {
        return ok(service.downloadUrl(id, authentication), request);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }
}
