package com.ramiart.admin.media.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.media.application.MediaModels.MediaAsset;
import com.ramiart.admin.media.application.MediaModels.MediaAssetPage;
import com.ramiart.admin.media.application.MediaModels.AssetReference;
import com.ramiart.admin.media.application.MediaModels.RequestMetadata;
import com.ramiart.admin.media.application.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/media-assets")
public final class MediaController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaController.class);
    private static final Set<String> CLIENT_STAGES = Set.of("RESPONSE_READY", "FORM_CHANGE_CALLED",
            "FORM_STATE_COMMITTED", "PREVIEW_LOADED", "PREVIEW_FAILED", "REFERENCES_STARTED", "REFERENCES_READY",
            "REFERENCES_FAILED", "CLIENT_ERROR", "PAGE_HIDDEN");
    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ApiEnvelope<MediaAssetPage>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "ALL") String usage,
            Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.list(page, size, keyword, usage, authentication), RequestIdFilter.get(request)));
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<MediaAsset>> upload(@RequestPart("file") MultipartFile file,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, Authentication authentication,
            HttpServletRequest request) throws IOException {
        MediaAsset asset = service.upload(file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                UUID.fromString(authentication.getName()), idempotencyKey, metadata(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(asset, RequestIdFilter.get(request)));
    }

    @DeleteMapping("/{assetId}")
    ResponseEntity<Void> delete(@PathVariable UUID assetId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, Authentication authentication,
            HttpServletRequest request) {
        service.delete(assetId, UUID.fromString(authentication.getName()), idempotencyKey, metadata(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/{assetId}/references")
    ResponseEntity<ApiEnvelope<java.util.List<AssetReference>>> references(@PathVariable UUID assetId,
            Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.references(assetId, authentication), RequestIdFilter.get(request)));
    }

    @PostMapping("/{assetId}/client-events")
    ResponseEntity<Void> clientEvent(@PathVariable UUID assetId, @RequestBody ClientEvent event,
            HttpServletRequest request) {
        if (event == null || !CLIENT_STAGES.contains(event.stage())) throw new IllegalArgumentException("Invalid media client stage");
        String detail = event.detail() == null ? "" : event.detail()
                .replaceAll("https?://\\S+", "[url]")
                .replaceAll("[\\p{Cntrl}]", " ");
        if (detail.length() > 300) detail = detail.substring(0, 300);
        LOGGER.info("media_client_event assetId={} stage={} detail={} requestId={}",
                assetId, event.stage(), detail, RequestIdFilter.get(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record ClientEvent(String stage, String detail) {}

    private static RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
        if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }
}

