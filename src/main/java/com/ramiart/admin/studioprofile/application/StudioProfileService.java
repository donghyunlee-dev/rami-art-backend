package com.ramiart.admin.studioprofile.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import static com.ramiart.admin.studioprofile.application.StudioProfileModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudioProfileService {
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
    private final StudioProfileRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public StudioProfileService(StudioProfileRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminView get(String mode, Authentication auth) {
        require(auth, "CONTENT_PROFILE_READ");
        if (!Set.of("DRAFT", "PUBLISHED").contains(mode)) throw new StudioProfileException("VALIDATION_ERROR");
        StoredProfile requested = repository.findByStatus(mode).orElse(null);
        if (requested != null) return admin(requested, "DRAFT".equals(requested.status()),
                "PUBLISHED".equals(requested.status()) && repository.findByStatus("DRAFT").isEmpty());
        if ("DRAFT".equals(mode)) {
            StoredProfile published = repository.findByStatus("PUBLISHED").orElseThrow(
                    () -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND"));
            return admin(published, false, true);
        }
        throw new StudioProfileException("STUDIO_PROFILE_NOT_FOUND");
    }

    @Transactional
    public Mutation<AdminView> createDraft(UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONTENT_PROFILE_WRITE");
        String scope = actor + ":STUDIO_PROFILE_DRAFT_CREATE";
        String hash = digest("POST:/api/admin/studio-profile/drafts");
        StudioProfileRepository.Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return new Mutation<>(admin(repository.findById(claim.resourceId()).orElseThrow(
                () -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND")), true, false), true);
        if (repository.findByStatus("DRAFT").isPresent()) throw new StudioProfileException("STUDIO_PROFILE_DRAFT_EXISTS");
        StoredProfile published = repository.findByStatus("PUBLISHED").orElse(null);
        Write initial = published == null ? emptyDraft() : from(published);
        UUID draftId = repository.insertDraft(initial, actor, published == null ? null : published.id());
        audit(actor, meta, "STUDIO_PROFILE_DRAFT_CREATED", draftId,
                Map.of("basedOnRevision", published == null ? 0 : published.revision()));
        repository.complete(scope, key, draftId, 201);
        return new Mutation<>(admin(repository.findById(draftId).orElseThrow(), true, false), false);
    }

    @Transactional
    public Mutation<AdminView> updateDraft(UUID id, Write raw, UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONTENT_PROFILE_WRITE");
        Write write = normalize(raw);
        validatePublishable(write);
        String scope = actor + ":STUDIO_PROFILE_UPDATE:" + id;
        String hash = digest("PUT:/api/admin/studio-profile/drafts/" + id + ":" + write);
        StudioProfileRepository.Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return new Mutation<>(admin(repository.findById(claim.resourceId()).orElseThrow(
                () -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND")), true, false), true);
        StoredProfile current = repository.findByIdForUpdate(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND"));
        if (raw.version() == null || raw.version() != current.version())
            throw new StudioProfileException("STUDIO_PROFILE_VERSION_CONFLICT");
        if (repository.updateDraft(id, write) != 1) throw new StudioProfileException("STUDIO_PROFILE_VERSION_CONFLICT");
        audit(actor, meta, "STUDIO_PROFILE_DRAFT_UPDATED", id, Map.of("revision", current.revision()));
        repository.complete(scope, key, id, 200);
        return new Mutation<>(admin(repository.findById(id).orElseThrow(), true, false), false);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id, long version, Authentication auth) {
        require(auth, "CONTENT_PROFILE_READ");
        StoredProfile draft = repository.findById(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND"));
        if (draft.version() != version) throw new StudioProfileException("STUDIO_PROFILE_VERSION_CONFLICT");
        List<BusinessHour> hours = hours(draft.businessHoursJson());
        List<Faq> faqs = faqs(draft.faqsJson());
        Coordinates coordinates = coordinates(draft);
        List<PlacementPreview> placements = List.of("HOME", "CONTACT", "FOOTER").stream()
                .map(placement -> new PlacementPreview(placement, draft.revision(), draft.studioName(),
                        draft.phone(), draft.email(), draft.address(), draft.addressDetail(), coordinates,
                        hours, draft.closedDays(), draft.transitGuide(), draft.parkingGuide(), faqs)).toList();
        return new Preview(draft.id(), draft.version(), placements);
    }

    @Transactional
    public Mutation<Publication> publish(PublicationRequest raw, UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONTENT_PROFILE_WRITE");
        if (raw == null || raw.draftId() == null || raw.draftVersion() == null || raw.draftVersion() < 0)
            throw new StudioProfileException("VALIDATION_ERROR");
        UUID id = raw.draftId();
        String scope = actor + ":STUDIO_PROFILE_PUBLISH:" + id;
        String hash = digest("POST:/api/admin/studio-profile/publications:" + raw.draftVersion());
        StudioProfileRepository.Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return new Mutation<>(publication(repository.findById(claim.resourceId()).orElseThrow(
                () -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND"))), true);
        StoredProfile draft = repository.findByIdForUpdate(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new StudioProfileException("STUDIO_PROFILE_NOT_FOUND"));
        if (draft.version() != raw.draftVersion()) throw new StudioProfileException("STUDIO_PROFILE_VERSION_CONFLICT");
        Write write = normalize(from(draft));
        validatePublishable(write);
        repository.publish(id, draft.version(), actor);
        audit(actor, meta, "STUDIO_PROFILE_PUBLISHED", id, Map.of("revision", draft.revision()));
        repository.complete(scope, key, id, 201);
        return new Mutation<>(publication(repository.findById(id).orElseThrow()), false);
    }

    @Transactional(readOnly = true)
    public PublicView publicProfile() {
        StoredProfile published = repository.findByStatus("PUBLISHED").orElseThrow(
                () -> new StudioProfileException("PUBLIC_STUDIO_PROFILE_NOT_FOUND"));
        return new PublicView(published.revision(), published.studioName(), published.phone(),
                published.email(), published.address(), published.addressDetail(), coordinates(published),
                hours(published.businessHoursJson()), published.closedDays(), published.transitGuide(),
                published.parkingGuide(), faqs(published.faqsJson()).stream().filter(Faq::visible).toList(), published.publishedAt());
    }

    private AdminView admin(StoredProfile profile, boolean editable, boolean createDraft) {
        boolean publishable = "DRAFT".equals(profile.status()) && isPublishable(from(profile));
        return new AdminView(profile.id(), editable ? profile.id() : null, profile.revision(), profile.status(),
                editable ? "DRAFT" : profile.status(), editable, profile.version(), profile.studioName(),
                profile.phone(), profile.email(), profile.address(), profile.addressDetail(), profile.latitude(),
                profile.longitude(), hours(profile.businessHoursJson()), profile.closedDays(), profile.transitGuide(),
                profile.parkingGuide(), faqs(profile.faqsJson()), publishable, new Actions(createDraft, editable, editable, editable, publishable),
                profile.updatedAt());
    }

    private boolean isPublishable(Write write) {
        try { validatePublishable(normalize(write)); return true; }
        catch (StudioProfileException exception) { return false; }
    }

    private static Write normalize(Write raw) {
        if (raw == null) throw new StudioProfileException("VALIDATION_ERROR");
        String phone = normalizePhone(raw.phone());
        String email = trim(raw.email());
        if (email != null) email = email.toLowerCase(Locale.ROOT);
        String addressDetail = optional(raw.addressDetail());
        String closedDays = optional(raw.closedDays());
        String transitGuide = optional(raw.transitGuide());
        String parkingGuide = optional(raw.parkingGuide());
        List<BusinessHour> businessHours = normalizeHours(raw.businessHours());
        List<Faq> faqs = normalizeFaqs(raw.faqs());
        return new Write(raw.version(), trim(raw.studioName()), phone, email, trim(raw.address()), addressDetail,
                raw.latitude(), raw.longitude(), businessHours, closedDays, transitGuide, parkingGuide, faqs);
    }

    private static void validatePublishable(Write write) {
        if (!length(write.studioName(), 1, 100)) throw new StudioProfileException("STUDIO_PROFILE_CONTACT_REQUIRED", "studioName");
        if (write.phone() == null || !write.phone().matches("\\+[1-9][0-9]{7,14}"))
            throw new StudioProfileException("STUDIO_PROFILE_CONTACT_REQUIRED", "phone");
        if (write.email() == null || write.email().length() > 254
                || !write.email().matches("(?i)^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$"))
            throw new StudioProfileException("STUDIO_PROFILE_CONTACT_REQUIRED", "email");
        if (!length(write.address(), 1, 200)) throw new StudioProfileException("STUDIO_PROFILE_LOCATION_INVALID", "address");
        if ((write.latitude() == null) != (write.longitude() == null)
                || write.latitude() != null && (write.latitude().compareTo(new java.math.BigDecimal("-90")) < 0
                || write.latitude().compareTo(new java.math.BigDecimal("90")) > 0
                || write.longitude().compareTo(new java.math.BigDecimal("-180")) < 0
                || write.longitude().compareTo(new java.math.BigDecimal("180")) > 0))
            throw new StudioProfileException("STUDIO_PROFILE_LOCATION_INVALID", "latitude");
        normalizeHours(write.businessHours());
        optionalLength(write.addressDetail(), 100, "addressDetail");
        optionalLength(write.closedDays(), 300, "closedDays");
        optionalLength(write.transitGuide(), 500, "transitGuide");
        optionalLength(write.parkingGuide(), 500, "parkingGuide");
        normalizeFaqs(write.faqs());
    }

    private static List<Faq> normalizeFaqs(List<Faq> values) {
        if (values == null || values.size() > 30) throw new StudioProfileException("VALIDATION_ERROR", "faqs");
        Set<UUID> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        List<Faq> result = new ArrayList<>();
        for (Faq faq : values) {
            if (faq == null || faq.faqId() == null || !ids.add(faq.faqId()) || faq.displayOrder() < 0
                    || !orders.add(faq.displayOrder())) throw new StudioProfileException("VALIDATION_ERROR", "faqs");
            String question = faq.question() == null ? null : faq.question().trim();
            String answer = faq.answer() == null ? null : faq.answer().trim();
            if (question == null || question.isEmpty() || question.length() > 200
                    || answer == null || answer.isEmpty() || answer.length() > 2000)
                throw new StudioProfileException("VALIDATION_ERROR", "faqs");
            result.add(new Faq(faq.faqId(), question, answer, faq.displayOrder(), faq.visible()));
        }
        result.sort(java.util.Comparator.comparingInt(Faq::displayOrder));
        return List.copyOf(result);
    }

    private static List<BusinessHour> normalizeHours(List<BusinessHour> values) {
        if (values == null || values.size() > 7) throw new StudioProfileException("STUDIO_PROFILE_HOURS_INVALID", "businessHours");
        Set<Integer> days = new HashSet<>();
        List<BusinessHour> result = new ArrayList<>();
        for (BusinessHour hour : values) {
            if (hour == null || hour.day() < 1 || hour.day() > 7 || !days.add(hour.day()))
                throw new StudioProfileException("STUDIO_PROFILE_HOURS_INVALID", "businessHours");
            if (hour.closed()) {
                if (hour.open() != null || hour.close() != null)
                    throw new StudioProfileException("STUDIO_PROFILE_HOURS_INVALID", "businessHours[" + hour.day() + "]");
                result.add(new BusinessHour(hour.day(), true, null, null));
            } else {
                try {
                    if (hour.open() == null || hour.close() == null
                            || !hour.open().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
                            || !hour.close().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
                        throw new IllegalArgumentException();
                    LocalTime open = LocalTime.parse(hour.open());
                    LocalTime close = LocalTime.parse(hour.close());
                    if (!open.isBefore(close)) throw new IllegalArgumentException();
                    result.add(new BusinessHour(hour.day(), false, open.toString(), close.toString()));
                } catch (RuntimeException exception) {
                    throw new StudioProfileException("STUDIO_PROFILE_HOURS_INVALID", "businessHours[" + hour.day() + "]");
                }
            }
        }
        return List.copyOf(result);
    }

    private static String normalizePhone(String input) {
        if (input == null) return null;
        String compact = input.trim().replaceAll("[\\s()-]", "");
        if (compact.matches("0[0-9]{8,10}")) compact = "+82" + compact.substring(1);
        if (!compact.matches("\\+[1-9][0-9]{7,14}"))
            throw new StudioProfileException("STUDIO_PROFILE_CONTACT_REQUIRED", "phone");
        return compact;
    }

    private static void optionalLength(String value, int max, String field) {
        if (value != null && (value.isBlank() || value.length() > max))
            throw new StudioProfileException("VALIDATION_ERROR", field);
    }
    private static boolean length(String value, int min, int max) { return value != null && value.length() >= min && value.length() <= max; }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static String optional(String value) { String trimmed = trim(value); return trimmed == null || trimmed.isEmpty() ? null : trimmed; }
    private static Write emptyDraft() { return new Write(0L, "", "", "", "", null, null, null, List.of(), null, null, null, List.of()); }
    private static Write from(StoredProfile p) { return new Write(p.version(), p.studioName(), p.phone(), p.email(), p.address(),
            p.addressDetail(), p.latitude(), p.longitude(), hours(p.businessHoursJson()), p.closedDays(), p.transitGuide(), p.parkingGuide(), faqs(p.faqsJson())); }
    private static Coordinates coordinates(StoredProfile p) { return p.latitude() == null ? null : new Coordinates(p.latitude(), p.longitude()); }
    private static Publication publication(StoredProfile p) { return new Publication(p.id(), p.revision(), p.status(), p.version(), p.publishedAt()); }
    private static List<BusinessHour> hours(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<BusinessHour>>() {}); }
        catch (com.fasterxml.jackson.core.JacksonException exception) { throw new IllegalStateException("Invalid business hours JSON", exception); }
    }
    private static List<Faq> faqs(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<Faq>>() {}); }
        catch (com.fasterxml.jackson.core.JacksonException exception) { throw new IllegalStateException("Invalid studio profile FAQs JSON", exception); }
    }
    private static String digest(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private void audit(UUID actor, RequestMetadata meta, String action, UUID id, Map<String, Object> details) {
        audit.record(new Event(clock.instant(), meta.requestId(), "MGT-CONTENT-STUDIO-PROFILE", "OPERATION",
                "ADMIN", actor, null, action, "STUDIO_PROFILE", id, "SUCCESS", null,
                meta.ipAddress(), meta.userAgent(), details));
    }
    private static UUID require(Authentication auth, String permission) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> permission.equals(a.getAuthority())))
            throw new StudioProfileException(permission + "_DENIED");
        try { return UUID.fromString(auth.getName()); }
        catch (RuntimeException exception) { throw new StudioProfileException(permission + "_DENIED"); }
    }
}
