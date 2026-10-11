package com.ramiart.admin.studioprofile.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import static com.ramiart.admin.studioprofile.application.StudioProfileModels.*;
import com.ramiart.admin.studioprofile.application.StudioProfileService;
import com.ramiart.admin.studioprofile.application.StudioProfileService.RequestMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StudioProfileController {
    private final StudioProfileService service;
    private final ObjectMapper mapper;
    public StudioProfileController(StudioProfileService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/api/admin/studio-profile")
    ResponseEntity<ApiEnvelope<AdminView>> get(@RequestParam String mode, Authentication auth,
            HttpServletRequest request) {
        return admin(service.get(mode, auth), request);
    }

    @PostMapping("/api/admin/studio-profile/drafts")
    ResponseEntity<ApiEnvelope<AdminView>> createDraft(@RequestHeader("Idempotency-Key") UUID key,
            Authentication auth, HttpServletRequest request) {
        Mutation<AdminView> result = service.createDraft(key, auth, metadata(request));
        return ResponseEntity.status(result.replay() ? 200 : 201).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.data(), RequestIdFilter.get(request)));
    }

    @PutMapping("/api/admin/studio-profile/drafts/{draftId}")
    ResponseEntity<ApiEnvelope<AdminView>> updateDraft(@PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body, Authentication auth,
            HttpServletRequest request) {
        return admin(service.updateDraft(draftId, parse(body,
                Set.of("version", "studioName", "phone", "email", "address", "addressDetail", "latitude",
                        "longitude", "businessHours", "closedDays", "transitGuide", "parkingGuide", "faqs"), Write.class),
                key, auth, metadata(request)).data(), request);
    }

    @GetMapping("/api/admin/studio-profile/drafts/{draftId}/preview")
    ResponseEntity<ApiEnvelope<Preview>> preview(@PathVariable UUID draftId, @RequestParam long version,
            Authentication auth, HttpServletRequest request) {
        return admin(service.preview(draftId, version, auth), request);
    }

    @PostMapping("/api/admin/studio-profile/publications")
    ResponseEntity<ApiEnvelope<Publication>> publish(@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody JsonNode rawBody, Authentication auth, HttpServletRequest request) {
        PublicationRequest body = parse(rawBody, Set.of("draftId", "draftVersion"), PublicationRequest.class);
        Mutation<Publication> result = service.publish(body, key, auth, metadata(request));
        return ResponseEntity.status(result.replay() ? 200 : 201).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.data(), RequestIdFilter.get(request)));
    }

    @GetMapping("/api/public/studio-profile")
    ResponseEntity<ApiEnvelope<PublicView>> publicProfile(HttpServletRequest request) {
        PublicView profile = service.publicProfile();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .eTag("\"studio-profile-" + profile.revision() + "\"")
                .body(ApiEnvelope.success(profile, RequestIdFilter.get(request)));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> admin(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) {
            userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
            if (userAgent.length() > 512) userAgent = userAgent.substring(0, 512);
        }
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), userAgent);
    }

    private <T> T parse(JsonNode raw, Set<String> allowed, Class<T> type) {
        if (raw == null || !raw.isObject()) throw new com.ramiart.admin.studioprofile.application.StudioProfileException("VALIDATION_ERROR");
        var fields = raw.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) throw new com.ramiart.admin.studioprofile.application.StudioProfileException(
                    "VALIDATION_ERROR", field);
        }
        if (raw.has("businessHours") && raw.get("businessHours").isArray()) {
            for (int i = 0; i < raw.get("businessHours").size(); i++) {
                JsonNode hour = raw.get("businessHours").get(i);
                if (!hour.isObject()) throw new com.ramiart.admin.studioprofile.application.StudioProfileException(
                        "STUDIO_PROFILE_HOURS_INVALID", "businessHours[" + i + "]");
                var hourFields = hour.fieldNames();
                while (hourFields.hasNext()) {
                    String field = hourFields.next();
                    if (!Set.of("day", "closed", "open", "close").contains(field))
                        throw new com.ramiart.admin.studioprofile.application.StudioProfileException(
                                "VALIDATION_ERROR", "businessHours[" + i + "]." + field);
                }
            }
        }
        if (raw.has("faqs") && raw.get("faqs").isArray()) {
            for (int i = 0; i < raw.get("faqs").size(); i++) {
                JsonNode faq = raw.get("faqs").get(i);
                if (!faq.isObject()) throw new com.ramiart.admin.studioprofile.application.StudioProfileException(
                        "VALIDATION_ERROR", "faqs[" + i + "]");
                Set<String> requiredFaqFields = Set.of("faqId", "question", "answer", "displayOrder", "visible");
                var faqFields = faq.fieldNames();
                while (faqFields.hasNext()) {
                    String field = faqFields.next();
                    if (!requiredFaqFields.contains(field))
                        throw new com.ramiart.admin.studioprofile.application.StudioProfileException(
                                "VALIDATION_ERROR", "faqs[" + i + "]." + field);
                }
                if (!requiredFaqFields.stream().allMatch(faq::has))
                    throw new com.ramiart.admin.studioprofile.application.StudioProfileException(
                            "VALIDATION_ERROR", "faqs[" + i + "]");
            }
        }
        try { return mapper.treeToValue(raw, type); }
        catch (com.fasterxml.jackson.core.JacksonException exception) {
            throw new com.ramiart.admin.studioprofile.application.StudioProfileException("VALIDATION_ERROR");
        }
    }
}
