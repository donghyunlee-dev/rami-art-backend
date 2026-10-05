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
        if (profile != null) return admin(profile, repository.careers(profile.id()), "DRAFT".equals(profile.status()),
                "PUBLISHED".equals(profile.status()) && repository.findByStatus("DRAFT").isEmpty());
        if ("DRAFT".equals(mode)) {
            StoredProfile published = repository.findByStatus("PUBLISHED").orElseThrow(
                    () -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
            return admin(published, repository.careers(published.id()), false, true);
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
        List<Career> copied = published == null ? List.of() : repository.careers(published.id()).stream()
                .map(c -> new Career(UUID.randomUUID(), c.period(), c.title(), c.displayOrder(), c.hidden())).toList();
        Write initial = published == null ? new Write(0L, "", "", "", List.of())
                : new Write(0L, published.name(), published.title(), published.introduction(), List.of());
        UUID id = repository.insertDraft(initial, actor, published == null ? null : published.id());
        repository.replaceCareers(id, copied);
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
        List<Career> careers = write.careers().stream().map(c -> new Career(c.id(), c.period(), c.title(),
                c.displayOrder(), c.hidden())).toList();
        if (repository.updateDraft(id, write) != 1) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        repository.replaceCareers(id, careers);
        audit(actor, meta, "DIRECTOR_PROFILE_DRAFT_UPDATED", id, Map.of("revision", current.revision(), "careerCount", careers.size()));
        repository.complete(scope, key, id, 200);
        return new Mutation<>(view(id), false);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id, long version, Authentication auth) {
        require(auth, "CONTENT_PROFILE_READ");
        StoredProfile draft = repository.findById(id).filter(p -> "DRAFT".equals(p.status()))
                .orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        if (draft.version() != version) throw new DirectorProfileException("DIRECTOR_PROFILE_VERSION_CONFLICT");
        List<Career> careers = repository.careers(id);
        return new Preview(admin(draft, careers, true, false), publicView(draft, careers));
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
        validate(normalize(new Write(draft.version(), draft.name(), draft.title(), draft.introduction(),
                repository.careers(id).stream().map(c -> new CareerWrite(c.id(), c.period(), c.title(), c.displayOrder(), c.hidden())).toList())));
        repository.publish(id, draft.version(), actor);
        audit(actor, meta, "DIRECTOR_PROFILE_PUBLISHED", id, Map.of("revision", draft.revision()));
        repository.complete(scope, key, id, 201);
        return new Mutation<>(publication(repository.findById(id).orElseThrow()), false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicView publicProfile() {
        StoredProfile profile = repository.findByStatus("PUBLISHED").orElseThrow(
                () -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        return publicView(profile, repository.careers(profile.id()));
    }

    private AdminView view(UUID id) {
        StoredProfile profile = repository.findById(id).orElseThrow(() -> new DirectorProfileException("DIRECTOR_PROFILE_NOT_FOUND"));
        List<Career> careers = repository.careers(id);
        return admin(profile, careers, "DRAFT".equals(profile.status()), false);
    }
    private AdminView admin(StoredProfile profile, List<Career> careers, boolean editable, boolean canCreate) {
        boolean publishable = "DRAFT".equals(profile.status()) && isPublishable(profile, careers);
        return new AdminView(profile.id(), editable ? profile.id() : null, profile.revision(), profile.status(),
                editable ? "DRAFT" : profile.status(), editable, profile.version(), profile.name(), profile.title(),
                profile.introduction(), careers, publishable,
                new Actions(canCreate, editable, editable, editable && publishable), profile.updatedAt());
    }
    private boolean isPublishable(StoredProfile profile, List<Career> careers) {
        try {
            validate(normalize(new Write(profile.version(), profile.name(), profile.title(), profile.introduction(),
                    careers.stream().map(c -> new CareerWrite(c.id(), c.period(), c.title(), c.displayOrder(), c.hidden())).toList())));
            return true;
        } catch (DirectorProfileException exception) { return false; }
    }
    private static PublicView publicView(StoredProfile profile, List<Career> careers) {
        return new PublicView(profile.revision(), profile.name(), profile.title(), profile.introduction(),
                careers.stream().filter(c -> !c.hidden()).map(c -> new PublicCareer(c.period(), c.title(), c.displayOrder())).toList(),
                profile.publishedAt());
    }
    private static Write normalize(Write raw) {
        if (raw == null) throw new DirectorProfileException("VALIDATION_ERROR");
        List<CareerWrite> careers = raw.careers() == null ? null : raw.careers().stream().map(c -> {
            if (c == null) throw new DirectorProfileException("DIRECTOR_PROFILE_INVALID", "careers");
            String period = trim(c.period());
            return new CareerWrite(c.id(), period == null || period.isEmpty() ? null : period, trim(c.title()), c.displayOrder(), c.hidden());
        }).toList();
        return new Write(raw.version(), trim(raw.name()), trim(raw.title()), trim(raw.introduction()), careers);
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
