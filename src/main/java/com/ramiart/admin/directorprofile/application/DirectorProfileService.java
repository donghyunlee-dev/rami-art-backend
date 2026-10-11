package com.ramiart.admin.directorprofile.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import static com.ramiart.admin.directorprofile.application.DirectorProfileModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DirectorProfileService {
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
    private record Content(List<Career> careers, List<Facility> facilities, List<AboutItem> items) {
        static Content empty() { return new Content(List.of(), List.of(), List.of()); }
    }
    private final DirectorProfileRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;
    public DirectorProfileService(DirectorProfileRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminView get(String mode, Authentication auth) {
        require(auth, "CONTENT_PROFILE_READ");
        if (!Set.of("DRAFT", "PUBLISHED").contains(mode)) throw new DirectorProfileException("VALIDATION_ERROR");
        StoredProfile profile = repository.findByStatus(mode).orElse(null);
        if (profile != null) return admin(profile, content(profile.id()), "DRAFT".equals(profile.status()),
                "PUBLISHED".equals(profile.status()) && repository.findByStatus("DRAFT").isEmpty());
        if ("DRAFT".equals(mode)) {
            StoredProfile published = repository.findByStatus("PUBLISHED").orElseThrow(
                    () -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
            return admin(published, content(published.id()), false, true);
        }
        throw new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND");
    }

    @Transactional
    public Mutation<AdminView> createDraft(UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONTENT_PROFILE_WRITE");
        String scope = actor + ":DIRECTOR_PROFILE_CREATE";
        DirectorProfileRepository.Claim claim = repository.claim(scope, key, digest("POST:/api/admin/director-profile/drafts"));
        if (!claim.claimed()) return new Mutation<>(view(claim.resourceId()), true);
        if (repository.findByStatus("DRAFT").isPresent()) throw new DirectorProfileException("DIRECTOR_PROFILE_DRAFT_EXISTS");
        StoredProfile published = repository.findByStatus("PUBLISHED").orElse(null);
        Content source = published == null ? Content.empty() : content(published.id());
        List<Career> copied = source.careers().stream()
                .map(c -> new Career(UUID.randomUUID(), c.period(), c.title(), c.displayOrder(), c.hidden())).toList();
        List<Facility> facilities = source.facilities().stream().map(f -> new Facility(UUID.randomUUID(), f.name(), f.description(), f.image(), f.displayOrder(), f.visible())).toList();
        List<AboutItem> items = source.items().stream().map(i -> new AboutItem(UUID.randomUUID(), i.itemType(), i.iconCode(), i.title(), i.description(), i.displayOrder(), i.visible())).toList();
        Write initial = published == null ? emptyWrite(0L) : from(published, 0L, source);
        UUID id = repository.insertDraft(initial, actor, published == null ? null : published.id());
        repository.replaceCareers(id, copied);
        repository.replaceAbout(id, facilities, items);
        audit(actor, meta, "DIRECTOR_PROFILE_DRAFT_CREATED", id,
                Map.of("basedOnRevision", published == null ? 0 : published.revision()));
        repository.complete(scope, key, id, 201);
        return new Mutation<>(view(id), false);
    }

    @Transactional
    public Mutation<AdminView> updateDraft(UUID id, Write raw, UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONTENT_PROFILE_WRITE");
        Write write = normalize(raw);
        String scope = actor + ":DIRECTOR_UPDATE:" + id;
        String hash = digest("PUT:/api/admin/director-profile/drafts/" + id + ":" + write);
        DirectorProfileRepository.Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return new Mutation<>(view(claim.resourceId()), true);
        StoredProfile current = repository.findByIdForUpdate(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        if (raw.version() == null || raw.version() != current.version())
            throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        validate(write);
        validateImageAvailability(write);
        List<Career> careers = write.careers().stream().map(c -> new Career(c.id(), c.period(), c.title(),
                c.displayOrder(), c.hidden())).toList();
        List<Facility> facilities = write.facilities().stream().map(f -> new Facility(f.id(), f.name(), f.description(), f.image(), f.displayOrder(), f.visible())).toList();
        List<AboutItem> items = write.educationItems().stream().map(i -> new AboutItem(i.id(), i.itemType(), i.iconCode(), i.title(), i.description(), i.displayOrder(), i.visible())).toList();
        if (repository.updateDraft(id, write) != 1) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        repository.replaceCareers(id, careers);
        repository.replaceAbout(id, facilities, items);
        audit(actor, meta, "DIRECTOR_PROFILE_DRAFT_UPDATED", id, Map.of("revision", current.revision(), "careerCount", careers.size(), "facilityCount", facilities.size(), "aboutItemCount", items.size()));
        repository.complete(scope, key, id, 200);
        return new Mutation<>(view(id), false);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id, long version, Authentication auth) {
        require(auth, "CONTENT_PROFILE_READ");
        StoredProfile draft = repository.findById(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        if (draft.version() != version) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        Content content = content(id);
        return new Preview(admin(draft, content, true, false), publicView(draft, content));
    }

    @Transactional
    public Mutation<Publication> publish(PublicationRequest raw, UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONTENT_PROFILE_WRITE");
        if (raw == null || raw.draftId() == null || raw.draftVersion() == null || raw.draftVersion() < 0)
            throw new DirectorProfileException("VALIDATION_ERROR");
        UUID id = raw.draftId();
        String scope = actor + ":DIRECTOR_PUBLISH:" + id;
        String hash = digest("POST:/api/admin/director-profile/publications:" + raw.draftVersion());
        DirectorProfileRepository.Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return new Mutation<>(publication(repository.findById(claim.resourceId()).orElseThrow(
                () -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"))), true);
        StoredProfile draft = repository.findByIdForUpdate(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        if (draft.version() != raw.draftVersion()) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        Write publishable = from(draft, draft.version(), content(id));
        validate(publishable);
        if (!isPublishable(draft, content(id))) throw new DirectorProfileException("DIRECTOR_PROFILE_NOT_PUBLISHABLE");
        repository.publish(id, draft.version(), actor);
        audit(actor, meta, "DIRECTOR_PROFILE_PUBLISHED", id, Map.of("revision", draft.revision()));
        repository.complete(scope, key, id, 201);
        return new Mutation<>(publication(repository.findById(id).orElseThrow()), false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicView publicProfile() {
        StoredProfile profile = repository.findByStatus("PUBLISHED").orElseThrow(
                () -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        return publicView(profile, content(profile.id()));
    }

    @Transactional(readOnly = true)
    public List<ConsentOption> consentOptions(Authentication auth) {
        require(auth, "CONSENT_READ");
        return repository.consentOptions();
    }

    private AdminView view(UUID id) {
        StoredProfile profile = repository.findById(id).orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        return admin(profile, content(id), "DRAFT".equals(profile.status()), false);
    }
    private AdminView admin(StoredProfile profile, Content content, boolean editable, boolean canCreate) {
        boolean publishable = "DRAFT".equals(profile.status()) && isPublishable(profile, content);
        return new AdminView(profile.id(), editable ? profile.id() : null, profile.revision(), profile.status(),
                editable ? "DRAFT" : profile.status(), editable, profile.version(), profile.name(), profile.title(),
                profile.introduction(), content.careers(), profile.about(), profile.philosophy(), profile.facilitySection(),
                profile.educationSection(), profile.direction(), profile.portrait(), content.facilities(), content.items(), publishable,
                new Actions(canCreate, editable, editable, editable && publishable), profile.updatedAt());
    }
    private boolean isPublishable(StoredProfile profile, Content content) {
        try {
            Write write = normalize(from(profile, profile.version(), content));
            validate(write);
            return !profile.name().isBlank() && !profile.title().isBlank() && !profile.introduction().isBlank()
                    && !profile.about().title().isBlank() && !profile.about().description().isBlank()
                    && !profile.philosophy().title().isBlank() && !profile.philosophy().description().isBlank()
                    && !profile.facilitySection().title().isBlank() && !profile.educationSection().title().isBlank()
                    && !profile.direction().title().isBlank() && !profile.direction().description().isBlank()
                    && content.facilities().stream().filter(Facility::visible).allMatch(f -> f.image().mediaAssetId() != null)
                    && repository.imageRightsValid(profile.id());
        } catch (DirectorProfileException exception) { return false; }
    }
    private PublicView publicView(StoredProfile profile, Content content) {
        return new PublicView(profile.revision(), profile.name(), profile.title(), profile.introduction(),
                content.careers().stream().filter(c -> !c.hidden()).map(c -> new PublicCareer(c.period(), c.title(), c.displayOrder())).toList(),
                profile.about(), profile.philosophy(), profile.facilitySection(), profile.educationSection(), profile.direction(),
                publicImage(profile.portrait()), content.facilities().stream().filter(Facility::visible)
                        .filter(f -> repository.imageCurrentlyPublic(f.image().mediaAssetId(), f.image().includesStudent(), f.image().studentConsentId(), true))
                        .map(f -> new PublicFacility(f.name(), f.description(), publicImage(f.image()), f.displayOrder())).toList(),
                content.items().stream().filter(AboutItem::visible).toList(),
                profile.publishedAt());
    }
    private static Write normalize(Write raw) {
        if (raw == null) throw new DirectorProfileException("VALIDATION_ERROR");
        List<CareerWrite> careers = raw.careers() == null ? null : raw.careers().stream().map(c -> {
            if (c == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers");
            String period = trim(c.period());
            return new CareerWrite(c.id(), period == null || period.isEmpty() ? null : period, trim(c.title()), c.displayOrder(), c.hidden());
        }).toList();
        List<FacilityWrite> facilities = raw.facilities() == null ? null : raw.facilities().stream().map(f -> {
            if (f == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "facilities");
            return new FacilityWrite(f.id(), trim(f.name()), trim(f.description()), normalizeImage(f.image()), f.displayOrder(), f.visible());
        }).toList();
        List<AboutItemWrite> items = raw.educationItems() == null ? null : raw.educationItems().stream().map(i -> {
            if (i == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "educationItems");
            return new AboutItemWrite(i.id(), i.itemType(), i.iconCode(), trim(i.title()), trim(i.description()), i.displayOrder(), i.visible());
        }).toList();
        return new Write(raw.version(), trim(raw.name()), trim(raw.title()), trim(raw.introduction()), careers,
                normalizeCopy(raw.about()), normalizeCopy(raw.philosophy()), normalizeCopy(raw.facilitySection()),
                normalizeCopy(raw.educationSection()), normalizeCopy(raw.direction()), normalizeImage(raw.portrait()), facilities, items);
    }
    private static void validate(Write write) {
        if (write.name() == null || write.name().isEmpty() || write.name().length() > 100)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "name");
        if (write.title() == null || write.title().isEmpty() || write.title().length() > 100)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "title");
        if (write.introduction() == null || write.introduction().isEmpty() || write.introduction().length() > 1000)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "introduction");
        if (write.careers() == null || write.careers().size() > 50)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers");
        validateCopy(write.about(), 80, 100, 500, "about");
        validateCopy(write.philosophy(), 80, 100, 1000, "philosophy");
        validateCopy(write.facilitySection(), 80, 100, 0, "facilitySection");
        validateCopy(write.educationSection(), 80, 100, 0, "educationSection");
        validateCopy(write.direction(), 0, 100, 1000, "direction");
        validateImage(write.portrait(), "portrait");
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < write.careers().size(); i++) {
            CareerWrite career = write.careers().get(i);
            if (career.id() == null || !ids.add(career.id())) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers[" + i + "].id");
            if (career.title() == null || career.title().isEmpty() || career.title().length() > 200)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers[" + i + "].title");
            if (career.period() != null && (career.period().isEmpty() || career.period().length() > 50))
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers[" + i + "].period");
            if (career.displayOrder() == null || career.displayOrder() != i)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers[" + i + "].displayOrder");
            if (career.hidden() == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers[" + i + "].hidden");
        }
        if (write.facilities() == null || write.facilities().size() > 12)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "facilities");
        Set<UUID> facilityIds = new HashSet<>();
        for (int i = 0; i < write.facilities().size(); i++) {
            FacilityWrite f = write.facilities().get(i);
            String field = "facilities[" + i + "]";
            if (f.id() == null || !facilityIds.add(f.id()) || f.displayOrder() == null || f.displayOrder() != i || f.visible() == null)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
            if (f.name() == null || f.name().length() > 100 || f.description() == null || f.description().length() > 500)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
            validateImage(f.image(), field + ".image");
            if (f.visible() && (f.name().isEmpty() || f.description().isEmpty() || f.image().mediaAssetId() == null))
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
        }
        if (write.educationItems() == null || write.educationItems().size() > 24)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "educationItems");
        Set<UUID> itemIds = new HashSet<>();
        Map<String, Integer> itemOrders = new java.util.HashMap<>();
        for (int i = 0; i < write.educationItems().size(); i++) {
            AboutItemWrite item = write.educationItems().get(i);
            String field = "educationItems[" + i + "]";
            if (item.id() == null || !itemIds.add(item.id()) || item.displayOrder() == null || item.visible() == null)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
            if (!Set.of("PHILOSOPHY_VALUE", "EDUCATION_VALUE", "EDUCATION_DIRECTION").contains(item.itemType()))
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field + ".itemType");
            int expectedOrder = itemOrders.getOrDefault(item.itemType(), 0);
            if (item.displayOrder() != expectedOrder || expectedOrder >= 8)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field + ".displayOrder");
            itemOrders.put(item.itemType(), expectedOrder + 1);
            if (item.title() == null || item.title().isEmpty() || item.title().length() > 200)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field + ".title");
            if ("EDUCATION_DIRECTION".equals(item.itemType())) {
                if (item.iconCode() != null || item.description() != null)
                    throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
            } else if (!Set.of("LIGHTBULB", "HEART", "EYE", "BRAIN", "PALETTE").contains(item.iconCode())
                    || item.description() == null || item.description().isEmpty() || item.description().length() > 1000) {
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
            }
        }
    }
    private static Copy normalizeCopy(Copy copy) { return copy == null ? new Copy("", "", "") : new Copy(trim(copy.eyebrow()), trim(copy.title()), trim(copy.description())); }
    private static Image normalizeImage(Image image) {
        if (image == null) return new Image(null, null, null, null, false, null);
        return new Image(image.mediaAssetId(), image.imageUrl(), trim(image.altText()), image.rightsBasis(), Boolean.TRUE.equals(image.includesStudent()), image.studentConsentId());
    }
    private static void validateCopy(Copy copy, int eyebrowLimit, int titleLimit, int descriptionLimit, String field) {
        if (copy == null || eyebrowLimit > 0 && (copy.eyebrow() == null || copy.eyebrow().length() > eyebrowLimit)
                || copy.title() == null || copy.title().length() > titleLimit
                || descriptionLimit > 0 && (copy.description() == null || copy.description().length() > descriptionLimit))
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
    }
    private static void validateImage(Image image, String field) {
        if (image == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
        boolean hasAsset = image.mediaAssetId() != null;
        if (!hasAsset && (image.altText() != null || image.rightsBasis() != null || image.includesStudent() || image.studentConsentId() != null))
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
        if (hasAsset && (image.altText() == null || image.altText().isEmpty() || image.altText().length() > 300
                || !Set.of("STUDIO_OWNED", "LICENSED", "ADULT_RELEASE", "STUDENT_CONSENT").contains(image.rightsBasis())
                || image.includesStudent() && (!"STUDENT_CONSENT".equals(image.rightsBasis()) || image.studentConsentId() == null)
                || !image.includesStudent() && ("STUDENT_CONSENT".equals(image.rightsBasis()) || image.studentConsentId() != null)))
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
    }
    private void validateImageAvailability(Write write) {
        if (!validImage(write.portrait())) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "portrait");
        for (int i = 0; i < write.facilities().size(); i++)
            if (!validImage(write.facilities().get(i).image()))
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "facilities[" + i + "].image");
    }
    private boolean validImage(Image image) {
        return image.mediaAssetId() == null || repository.imageCurrentlyPublic(
                image.mediaAssetId(), image.includesStudent(), image.studentConsentId(), false);
    }
    private PublicImage publicImage(Image image) {
        return image == null || !repository.imageCurrentlyPublic(image.mediaAssetId(), image.includesStudent(), image.studentConsentId(), true)
                ? null : new PublicImage(image.imageUrl(), image.altText());
    }
    private Content content(UUID id) { return new Content(repository.careers(id), repository.facilities(id), repository.aboutItems(id)); }
    private static Write emptyWrite(long version) {
        return new Write(version, "", "", "", List.of(), new Copy("", "", ""), new Copy("", "", ""),
                new Copy("", "", ""), new Copy("", "", ""), new Copy(null, "", ""),
                new Image(null, null, null, null, false, null), List.of(), List.of());
    }
    private static Write from(StoredProfile p, long version, Content content) {
        return new Write(version, p.name(), p.title(), p.introduction(),
                content.careers().stream().map(c -> new CareerWrite(c.id(), c.period(), c.title(), c.displayOrder(), c.hidden())).toList(),
                p.about(), p.philosophy(), p.facilitySection(), p.educationSection(), p.direction(), p.portrait(),
                content.facilities().stream().map(f -> new FacilityWrite(f.id(), f.name(), f.description(), f.image(), f.displayOrder(), f.visible())).toList(),
                content.items().stream().map(i -> new AboutItemWrite(i.id(), i.itemType(), i.iconCode(), i.title(), i.description(), i.displayOrder(), i.visible())).toList());
    }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static Publication publication(StoredProfile p) { return new Publication(p.id(), p.revision(), p.status(), p.version(), p.publishedAt()); }
    private static String digest(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private void audit(UUID actor, RequestMetadata meta, String action, UUID id, Map<String, Object> details) {
        audit.record(new Event(clock.instant(), meta.requestId(), "MGT-CONTENT-DIRECTOR-PROFILE", "OPERATION",
                "ADMIN", actor, null, action, "DIRECTOR_PROFILE", id, "SUCCESS", null,
                meta.ipAddress(), meta.userAgent(), details));
    }
    private static UUID require(Authentication auth, String permission) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> permission.equals(a.getAuthority())))
            throw new DirectorProfileException("CONTENT_PROFILE_DENIED");
        try { return UUID.fromString(auth.getName()); }
        catch (RuntimeException exception) { throw new DirectorProfileException("CONTENT_PROFILE_DENIED"); }
    }
}
