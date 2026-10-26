package com.ramiart.admin.directorprofile.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.media.application.MediaUrlResolver;
import static com.ramiart.admin.directorprofile.application.DirectorProfileModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashSet;
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
    private record Content(List<Career> careers, List<StudioImage> images) {}
    private final DirectorProfileRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;
    private final MediaUrlResolver mediaUrls;

    public DirectorProfileService(DirectorProfileRepository repository, AuditRecorder audit, Clock clock,
            MediaUrlResolver mediaUrls) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.mediaUrls = mediaUrls;
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
        Content source = published == null ? new Content(List.of(), List.of()) : content(published.id());
        List<Career> careers = source.careers().stream()
                .map(c -> new Career(UUID.randomUUID(), c.period(), c.title(), c.displayOrder())).toList();
        List<StudioImage> images = source.images().stream().map(i -> new StudioImage(UUID.randomUUID(),
                i.mediaAssetId(), i.imageUrl(), i.altText(), i.description(), i.displayOrder())).toList();
        UUID id = repository.insertDraft(published == null ? emptyWrite(0L) : from(published, 0L, source), actor,
                published == null ? null : published.id());
        repository.replaceCareers(id, careers);
        repository.replaceStudioImages(id, images);
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
        if (!validImage(write.portrait())) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "portrait");
        for (int i = 0; i < write.studioImages().size(); i++)
            if (!repository.imageCurrentlyPublic(write.studioImages().get(i).mediaAssetId()))
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "studioImages[" + i + "].mediaAssetId");
        List<Career> careers = write.careers().stream().map(c -> new Career(c.id(), c.period(), c.title(), c.displayOrder())).toList();
        List<StudioImage> images = write.studioImages().stream().map(i -> new StudioImage(i.id(), i.mediaAssetId(),
                i.imageUrl(), i.altText(), i.description(), i.displayOrder())).toList();
        if (repository.updateDraft(id, write) != 1) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        repository.replaceCareers(id, careers);
        repository.replaceStudioImages(id, images);
        audit(actor, meta, "DIRECTOR_PROFILE_DRAFT_UPDATED", id, Map.of("revision", current.revision(),
                "careerCount", careers.size(), "studioImageCount", images.size()));
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
        Content content = content(id);
        validate(from(draft, draft.version(), content));
        if (!isPublishable(draft, content)) throw new DirectorProfileException("DIRECTOR_PROFILE_NOT_PUBLISHABLE");
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

    private AdminView view(UUID id) {
        StoredProfile profile = repository.findById(id).orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        return admin(profile, content(id), "DRAFT".equals(profile.status()), false);
    }
    private AdminView admin(StoredProfile profile, Content content, boolean editable, boolean canCreate) {
        boolean publishable = "DRAFT".equals(profile.status()) && isPublishable(profile, content);
        return new AdminView(profile.id(), editable ? profile.id() : null, profile.revision(), profile.status(),
                editable ? "DRAFT" : profile.status(), editable, profile.version(), profile.name(), profile.introduction(),
                profile.studioIntroduction(), content.careers(), resolveImage(profile.portrait()),
                content.images().stream().map(this::resolveImage).toList(), publishable,
                new Actions(canCreate, editable, editable, editable && publishable), profile.updatedAt());
    }
    private boolean isPublishable(StoredProfile profile, Content content) {
        return !profile.name().isBlank() && !profile.introduction().isBlank() && !profile.studioIntroduction().isBlank()
                && content.images().stream().allMatch(i -> i.mediaAssetId() != null
                    && i.altText() != null && !i.altText().isBlank()
                    && i.description() != null && !i.description().isBlank()
                    && repository.imageCurrentlyPublic(i.mediaAssetId()))
                && (profile.portrait().mediaAssetId() == null || repository.imageCurrentlyPublic(profile.portrait().mediaAssetId()));
    }
    private PublicView publicView(StoredProfile profile, Content content) {
        PublicImage portrait = profile.portrait().mediaAssetId() != null
                && repository.imageCurrentlyPublic(profile.portrait().mediaAssetId())
                ? new PublicImage(mediaUrls.resolve(profile.portrait().imageUrl()), profile.portrait().altText()) : null;
        List<PublicStudioImage> images = content.images().stream()
                .filter(i -> repository.imageCurrentlyPublic(i.mediaAssetId()))
                .map(i -> new PublicStudioImage(mediaUrls.resolve(i.imageUrl()), i.altText(), i.description(), i.displayOrder())).toList();
        return new PublicView(profile.revision(), profile.name(), profile.introduction(), profile.studioIntroduction(),
                content.careers().stream().map(c -> new PublicCareer(c.period(), c.title(), c.displayOrder())).toList(),
                portrait, images, profile.publishedAt());
    }
    private static Write normalize(Write raw) {
        if (raw == null) throw new DirectorProfileException("VALIDATION_ERROR");
        List<CareerWrite> careers = raw.careers() == null ? null : raw.careers().stream().map(c -> {
            if (c == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers");
            String period = trim(c.period());
            return new CareerWrite(c.id(), period == null || period.isEmpty() ? null : period, trim(c.title()), c.displayOrder());
        }).toList();
        List<StudioImageWrite> images = raw.studioImages() == null ? null : raw.studioImages().stream().map(i -> {
            if (i == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "studioImages");
            return new StudioImageWrite(i.id(), i.mediaAssetId(), i.imageUrl(), trim(i.altText()), trim(i.description()), i.displayOrder());
        }).toList();
        Image portrait = raw.portrait() == null ? new Image(null, null, null)
                : new Image(raw.portrait().mediaAssetId(), raw.portrait().imageUrl(), trim(raw.portrait().altText()));
        return new Write(raw.version(), trim(raw.name()), trim(raw.introduction()), trim(raw.studioIntroduction()), careers, portrait, images);
    }
    private static void validate(Write write) {
        if (write.name() == null || write.name().isEmpty() || write.name().length() > 100)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "name");
        if (write.introduction() == null || write.introduction().isEmpty() || write.introduction().length() > 1000)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "introduction");
        if (write.studioIntroduction() == null || write.studioIntroduction().isEmpty() || write.studioIntroduction().length() > 2000)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "studioIntroduction");
        if (write.careers() == null || write.careers().size() > 50 || write.studioImages() == null || write.studioImages().size() > 12)
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID");
        validateImage(write.portrait(), "portrait", false);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < write.careers().size(); i++) {
            CareerWrite career = write.careers().get(i);
            String field = "careers[" + i + "]";
            if (career.id() == null || !ids.add(career.id()) || career.title() == null || career.title().isEmpty()
                    || career.title().length() > 200 || career.period() != null && (career.period().isEmpty() || career.period().length() > 50)
                    || career.displayOrder() == null || career.displayOrder() != i)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
        }
        ids.clear();
        for (int i = 0; i < write.studioImages().size(); i++) {
            StudioImageWrite image = write.studioImages().get(i);
            String field = "studioImages[" + i + "]";
            if (image.id() == null || !ids.add(image.id()) || image.mediaAssetId() == null
                    || image.altText() == null || image.altText().isEmpty() || image.altText().length() > 300
                    || image.description() == null || image.description().isEmpty() || image.description().length() > 500
                    || image.displayOrder() == null || image.displayOrder() != i)
                throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
        }
    }
    private static void validateImage(Image image, String field, boolean required) {
        if (image == null || required && image.mediaAssetId() == null
                || image.mediaAssetId() == null && image.altText() != null
                || image.mediaAssetId() != null && (image.altText() == null || image.altText().isEmpty() || image.altText().length() > 300))
            throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", field);
    }
    private boolean validImage(Image image) {
        return image.mediaAssetId() == null || repository.imageCurrentlyPublic(image.mediaAssetId());
    }
    private Image resolveImage(Image image) {
        return image == null ? null : new Image(image.mediaAssetId(), mediaUrls.resolve(image.imageUrl()), image.altText());
    }
    private StudioImage resolveImage(StudioImage image) {
        return new StudioImage(image.id(), image.mediaAssetId(), mediaUrls.resolve(image.imageUrl()), image.altText(), image.description(), image.displayOrder());
    }
    private Content content(UUID id) { return new Content(repository.careers(id), repository.studioImages(id)); }
    private static Write emptyWrite(long version) {
        return new Write(version, "", "", "", List.of(), new Image(null, null, null), List.of());
    }
    private static Write from(StoredProfile p, long version, Content content) {
        return new Write(version, p.name(), p.introduction(), p.studioIntroduction(),
                content.careers().stream().map(c -> new CareerWrite(c.id(), c.period(), c.title(), c.displayOrder())).toList(),
                p.portrait(), content.images().stream().map(i -> new StudioImageWrite(i.id(), i.mediaAssetId(), i.imageUrl(),
                        i.altText(), i.description(), i.displayOrder())).toList());
    }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static Publication publication(StoredProfile p) { return new Publication(p.id(), p.revision(), p.status(), p.version(), p.publishedAt()); }
    private static String digest(String source) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
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
