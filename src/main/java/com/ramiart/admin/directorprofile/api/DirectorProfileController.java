package com.ramiart.admin.directorprofile.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import static com.ramiart.admin.directorprofile.application.DirectorProfileModels.*;
import com.ramiart.admin.directorprofile.application.DirectorProfileException;
import com.ramiart.admin.directorprofile.application.DirectorProfileService;
import com.ramiart.admin.directorprofile.application.DirectorProfileService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DirectorProfileController {
    private static final Set<String> WRITE_FIELDS = Set.of("version", "name", "title", "introduction", "careers", "about",
            "philosophy", "facilitySection", "educationSection", "direction", "portrait", "facilities", "educationItems");
    private final DirectorProfileService service;
    private final ObjectMapper mapper;
    public DirectorProfileController(DirectorProfileService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }

    @GetMapping("/api/admin/director-profile")
    ResponseEntity<ApiEnvelope<AdminView>> get(@RequestParam String mode, Authentication auth, HttpServletRequest request) {
        return admin(service.get(mode, auth), request);
    }
    @PostMapping("/api/admin/director-profile/drafts")
    ResponseEntity<ApiEnvelope<AdminView>> create(@RequestHeader("Idempotency-Key") UUID key,
            Authentication auth, HttpServletRequest request) {
        Mutation<AdminView> result = service.createDraft(key, auth, metadata(request));
        return ResponseEntity.status(result.replay() ? 200 : 201).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.data(), RequestIdFilter.get(request)));
    }
    @PutMapping("/api/admin/director-profile/drafts/{draftId}")
    ResponseEntity<ApiEnvelope<AdminView>> update(@PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode raw, Authentication auth,
            HttpServletRequest request) {
        Write write = parseWrite(raw);
        return admin(service.updateDraft(draftId, write, key, auth, metadata(request)).data(), request);
    }
    @GetMapping("/api/admin/director-profile/drafts/{draftId}/preview")
    ResponseEntity<ApiEnvelope<Preview>> preview(@PathVariable UUID draftId, @RequestParam long version,
            Authentication auth, HttpServletRequest request) {
        return admin(service.preview(draftId, version, auth), request);
    }
    @GetMapping("/api/admin/director-profile/consent-options")
    ResponseEntity<ApiEnvelope<java.util.List<ConsentOption>>> consentOptions(Authentication auth, HttpServletRequest request) {
        return admin(service.consentOptions(auth), request);
    }
    @PostMapping("/api/admin/director-profile/publications")
    ResponseEntity<ApiEnvelope<Publication>> publish(@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody JsonNode raw, Authentication auth, HttpServletRequest request) {
        PublicationRequest body = parse(raw, Set.of("draftId", "draftVersion"), PublicationRequest.class);
        Mutation<Publication> result = service.publish(body, key, auth, metadata(request));
        return ResponseEntity.status(result.replay() ? 200 : 201).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result.data(), RequestIdFilter.get(request)));
    }
    @GetMapping("/api/public/director-profile")
    ResponseEntity<ApiEnvelope<PublicView>> publicProfile(HttpServletRequest request) {
        PublicView view = service.publicProfile();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .eTag("\"director-profile-" + view.revision() + "\"")
                .body(ApiEnvelope.success(view, RequestIdFilter.get(request)));
    }

    private Write parseWrite(JsonNode raw) {
        validateKeys(raw, WRITE_FIELDS);
        JsonNode careers = raw.get("careers");
        if (careers != null && careers.isArray()) {
            for (int i = 0; i < careers.size(); i++) validateKeys(careers.get(i), Set.of("id", "period", "title", "displayOrder", "hidden"));
        }
        for (String field : Set.of("about", "philosophy", "facilitySection", "educationSection", "direction"))
            validateKeys(raw.get(field), Set.of("eyebrow", "title", "description"));
        validateKeys(raw.get("portrait"), Set.of("mediaAssetId", "imageUrl", "altText", "rightsBasis", "includesStudent", "studentConsentId"));
        JsonNode facilities = raw.get("facilities");
        if (facilities != null && facilities.isArray()) for (int i = 0; i < facilities.size(); i++) {
            JsonNode facility = facilities.get(i);
            validateKeys(facility, Set.of("id", "name", "description", "image", "displayOrder", "visible"));
            validateKeys(facility.get("image"), Set.of("mediaAssetId", "imageUrl", "altText", "rightsBasis", "includesStudent", "studentConsentId"));
        }
        JsonNode items = raw.get("educationItems");
        if (items != null && items.isArray()) for (int i = 0; i < items.size(); i++)
            validateKeys(items.get(i), Set.of("id", "itemType", "iconCode", "title", "description", "displayOrder", "visible"));
        return convert(raw, Write.class);
    }
    private <T> T parse(JsonNode raw, Set<String> fields, Class<T> type) {
        validateKeys(raw, fields);
        return convert(raw, type);
    }
    private void validateKeys(JsonNode raw, Set<String> allowed) {
        if (raw == null || !raw.isObject()) throw new DirectorProfileException("VALIDATION_ERROR");
        var names = raw.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) throw new DirectorProfileException("VALIDATION_ERROR", field);
        }
    }
    private <T> T convert(JsonNode raw, Class<T> type) {
        try { return mapper.treeToValue(raw, type); }
        catch (com.fasterxml.jackson.core.JacksonException exception) { throw new DirectorProfileException("VALIDATION_ERROR"); }
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> admin(T value, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(value, RequestIdFilter.get(request)));
    }
    private static RequestMetadata metadata(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent != null) { agent = agent.replaceAll("[\\p{Cntrl}]", ""); if (agent.length() > 512) agent = agent.substring(0, 512); }
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), agent);
    }
}
