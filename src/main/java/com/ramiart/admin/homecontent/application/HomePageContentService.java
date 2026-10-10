package com.ramiart.admin.homecontent.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import static com.ramiart.admin.homecontent.application.HomePageContentModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
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
public class HomePageContentService {
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
    private static final Set<String> CTA_TARGETS = Set.of("CONTACT_INQUIRY", "CLASSES", "GALLERY_WORKS", "BLOG");
    private static final Set<String> ICONS = Set.of("PALETTE", "USERS", "SPARKLES");
    private static final List<String> SECTION_KEYS = List.of("HERO", "STRENGTHS", "PROGRAMS", "GALLERY", "BLOG", "LOCATION", "CONTACT");
    private final HomePageContentRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public HomePageContentService(HomePageContentRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminView get(Authentication auth) {
        require(auth, "HOME_CONTENT_READ");
        return new AdminView(repository.findByStatus("PUBLISHED").orElse(null),
                repository.findByStatus("DRAFT").orElse(null), repository.history());
    }

    @Transactional(readOnly = true)
    public Options options(Authentication auth) {
        require(auth, "HOME_CONTENT_READ");
        return repository.options();
    }

    @Transactional(readOnly = true)
    public RevisionView revision(UUID id, Authentication auth) {
        require(auth, "HOME_CONTENT_READ");
        return repository.findById(id).orElseThrow(() -> new HomePageContentException("HOME_CONTENT_REVISION_NOT_FOUND"));
    }

    @Transactional
    public RevisionView createDraft(UUID sourceRevisionId, UUID actor, UUID key, RequestMetadata meta) {
        String scope = actor + ":HOME_CONTENT_DRAFT_CREATE";
        Claim claim = repository.claim(scope, key, digest("POST:/api/admin/home-page-content/drafts:" + sourceRevisionId));
        if (!claim.claimed()) return repository.findById(claim.resourceId()).orElseThrow(() -> new HomePageContentException("HOME_CONTENT_REVISION_NOT_FOUND"));
        if (repository.findByStatus("DRAFT").isPresent()) throw new HomePageContentException("HOME_CONTENT_DRAFT_EXISTS");
        RevisionView base = sourceRevisionId == null
                ? repository.findByStatus("PUBLISHED").orElse(null)
                : repository.findById(sourceRevisionId).orElseThrow(() -> new HomePageContentException("HOME_CONTENT_REVISION_NOT_FOUND"));
        HomePageWrite copy = base == null ? empty() : toWrite(base);
        UUID id = repository.insertDraft(copy, actor, base == null ? null : base.id());
        repository.replaceChildren(id, copy);
        record(actor, meta, "HOME_CONTENT_DRAFT_CREATED", id, Map.of("basedOnRevision", base == null ? 0 : base.revision()));
        repository.complete(scope, key, id, 201);
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public RevisionView updateDraft(UUID id, HomePageWrite raw, UUID actor, UUID key, RequestMetadata meta) {
        HomePageWrite write = normalize(raw);
        validateStructure(write);
        String scope = actor + ":HOME_CONTENT_DRAFT_UPDATE:" + id;
        Claim claim = repository.claim(scope, key, digest("PUT:/api/admin/home-page-content/drafts/" + id + ":" + write));
        if (!claim.claimed()) return repository.findById(id).orElseThrow(() -> new HomePageContentException("HOME_CONTENT_DRAFT_NOT_FOUND"));
        RevisionView current = repository.findById(id).filter(v -> "DRAFT".equals(v.status()))
                .orElseThrow(() -> new HomePageContentException("HOME_CONTENT_DRAFT_NOT_FOUND"));
        if (write.version() == null || write.version() != current.version()) throw new HomePageContentException("HOME_CONTENT_VERSION_CONFLICT");
        if (repository.updateDraft(id, write) != 1) throw new HomePageContentException("HOME_CONTENT_VERSION_CONFLICT");
        repository.replaceChildren(id, write);
        record(actor, meta, "HOME_CONTENT_DRAFT_UPDATED", id, Map.of("revision", current.revision()));
        repository.complete(scope, key, id, 200);
        return repository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id, long version, Authentication auth) {
        require(auth, "HOME_CONTENT_READ");
        RevisionView draft = repository.findById(id).filter(v -> "DRAFT".equals(v.status()))
                .orElseThrow(() -> new HomePageContentException("HOME_CONTENT_DRAFT_NOT_FOUND"));
        if (draft.version() != version) throw new HomePageContentException("HOME_CONTENT_VERSION_CONFLICT");
        List<ValidationError> errors = validate(draft);
        return new Preview(draft, errors, errors.isEmpty());
    }

    @Transactional
    public Publication publish(UUID id, long version, UUID actor, UUID key, RequestMetadata meta) {
        String scope = actor + ":HOME_CONTENT_PUBLISH:" + id;
        Claim claim = repository.claim(scope, key, digest("POST:/api/admin/home-page-content/publications:" + id + ":" + version));
        if (!claim.claimed()) {
            RevisionView replay = repository.findById(claim.resourceId()).orElseThrow(() -> new HomePageContentException("HOME_CONTENT_REVISION_NOT_FOUND"));
            return new Publication(replay.id(), replay.revision(), replay.status(), replay.publishedAt());
        }
        RevisionView draft = repository.findById(id).filter(v -> "DRAFT".equals(v.status()))
                .orElseThrow(() -> new HomePageContentException("HOME_CONTENT_DRAFT_NOT_FOUND"));
        if (draft.version() != version) throw new HomePageContentException("HOME_CONTENT_VERSION_CONFLICT");
        List<ValidationError> errors = validate(draft);
        if (!errors.isEmpty()) throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", errors.get(0).errorCode());
        repository.publish(id, version, actor);
        record(actor, meta, "HOME_CONTENT_PUBLISHED", id, Map.of("revision", draft.revision()));
        repository.complete(scope, key, id, 201);
        RevisionView published = repository.findById(id).orElseThrow();
        return new Publication(published.id(), published.revision(), published.status(), published.publishedAt());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicView publicContent() {
        return repository.findPublic().orElseThrow(() -> new HomePageContentException("HOME_CONTENT_PUBLIC_NOT_FOUND"));
    }

    private List<ValidationError> validate(RevisionView draft) {
        HomePageWrite write = toWrite(draft);
        try { validateStructure(write); }
        catch (HomePageContentException error) {
            return List.of(new ValidationError("CONTENT", draft.id(), error.code(), error.field() == null ? error.code() : error.field()));
        }
        return repository.validateReferences(draft);
    }

    private static HomePageWrite normalize(HomePageWrite write) {
        if (write == null || write.hero() == null || write.references() == null || write.strengths() == null || write.sections() == null)
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "payload");
        if (write.references().courses() == null || write.references().artworks() == null || write.references().posts() == null
                || write.strengths().stream().anyMatch(java.util.Objects::isNull)
                || write.sections().stream().anyMatch(java.util.Objects::isNull))
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "payload");
        Hero hero = write.hero();
        return new HomePageWrite(write.version(), new Hero(trim(hero.title()), trim(hero.description()), hero.mediaAssetId(), null,
                trim(hero.altText()), trim(hero.ctaLabel()), trim(hero.ctaTarget())),
                write.strengths().stream().map(s -> new StrengthWrite(s.iconCode(), trim(s.title()), trim(s.description()), s.displayOrder())).toList(),
                write.sections(), write.references());
    }

    private static void validateStructure(HomePageWrite w) {
        Hero h = w.hero();
        if (!length(h.title(), 1, 100) || !length(h.description(), 1, 300) || h.mediaAssetId() == null
                || !length(h.altText(), 1, 300) || !length(h.ctaLabel(), 1, 40) || !CTA_TARGETS.contains(h.ctaTarget()))
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "hero");
        if (w.strengths().isEmpty() || w.strengths().size() > 6) throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "strengths");
        for (int i = 0; i < w.strengths().size(); i++) {
            StrengthWrite s = w.strengths().get(i);
            if (s.displayOrder() != i || !ICONS.contains(s.iconCode()) || !length(s.title(), 1, 80) || !length(s.description(), 1, 300))
                throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "strengths[" + i + "]");
        }
        if (w.sections().size() != SECTION_KEYS.size()) throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "sections");
        if (w.sections().stream().map(SectionWrite::sectionKey).distinct().count() != SECTION_KEYS.size())
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "sections");
        boolean[] orders = new boolean[SECTION_KEYS.size()];
        for (SectionWrite s : w.sections()) {
            if (!SECTION_KEYS.contains(s.sectionKey()) || s.displayOrder() < 0 || s.displayOrder() >= orders.length || orders[s.displayOrder()])
                throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "sections");
            orders[s.displayOrder()] = true;
        }
        if (w.sections().stream().noneMatch(s -> "HERO".equals(s.sectionKey()) && s.visible()))
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "sections.HERO");
        if (w.references().courses().size() > 3 || w.references().artworks().size() > 4 || w.references().posts().size() > 3)
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "references");
        if (w.references().courses().stream().distinct().count() != w.references().courses().size()
                || w.references().artworks().stream().distinct().count() != w.references().artworks().size()
                || w.references().posts().stream().distinct().count() != w.references().posts().size())
            throw new HomePageContentException("HOME_CONTENT_NOT_PUBLISHABLE", "references");
    }

    private void record(UUID actor, RequestMetadata meta, String action, UUID id, Map<String,Object> details) {
        audit.record(new Event(clock.instant(), meta.requestId(), "MGT-CONTENT-HOME", "OPERATION", "ADMIN", actor,
                null, action, "HOME_PAGE_CONTENT", id, "SUCCESS", null, meta.ipAddress(), meta.userAgent(), details));
    }
    private static UUID require(Authentication auth, String permission) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> permission.equals(a.getAuthority())))
            throw new HomePageContentException("HOME_CONTENT_READ_DENIED");
        return UUID.fromString(auth.getName());
    }
    private static HomePageWrite empty() {
        return new HomePageWrite(0L, new Hero(null, null, null, null, null, null, null), List.of(),
                SECTION_KEYS.stream().map(k -> new SectionWrite(k, true, SECTION_KEYS.indexOf(k))).toList(),
                new ReferencesWrite(List.of(), List.of(), List.of()));
    }
    private static HomePageWrite toWrite(RevisionView v) {
        return new HomePageWrite(v.version(), v.hero(), v.strengths().stream().map(s -> new StrengthWrite(s.iconCode(), s.title(), s.description(), s.displayOrder())).toList(),
                v.sections().stream().map(s -> new SectionWrite(s.sectionKey(), s.visible(), s.displayOrder())).toList(),
                new ReferencesWrite(v.references().courses().stream().map(HomeReference::id).toList(),
                        v.references().artworks().stream().map(HomeReference::id).toList(), v.references().posts().stream().map(HomeReference::id).toList()));
    }
    private static boolean length(String value, int min, int max) { return value != null && value.length() >= min && value.length() <= max; }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
