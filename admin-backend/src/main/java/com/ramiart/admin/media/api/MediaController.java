package com.ramiart.admin.media.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.media.application.MediaModels.MediaAsset;
import com.ramiart.admin.media.application.MediaModels.RequestMetadata;
import com.ramiart.admin.media.application.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/media-assets")
public final class MediaController {
    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
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

    private static RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
        if (userAgent != null && userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }
}

