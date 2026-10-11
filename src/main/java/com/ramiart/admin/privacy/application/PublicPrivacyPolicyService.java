package com.ramiart.admin.privacy.application;

import static com.ramiart.admin.privacy.application.PublicPrivacyPolicyModels.*;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.retention.application.RetentionService;
import com.ramiart.admin.privacy.application.PublicPrivacyPolicyRepository.Claim;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicPrivacyPolicyService {
    private static final int INTERNAL_RETENTION_MONTHS = RetentionService.INQUIRY_RETENTION_MONTHS;
    private static final String INTERNAL_RETENTION_ANCHOR = RetentionService.INQUIRY_RETENTION_ANCHOR;
    private static final Set<String> RETENTION_ANCHORS = Set.of("RECEIVED", "CONSULTATION_COMPLETED");
    private final PublicPrivacyPolicyRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public PublicPrivacyPolicyService(PublicPrivacyPolicyRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Policies policies(Authentication auth) {
        require(auth, "CONSENT_READ");
        return new Policies(repository.findAll());
    }

    @Transactional(readOnly = true)
    public Policy publicCurrent() {
        return repository.findPublished().filter(policy -> !policy.effectiveOn().isAfter(LocalDate.now(clock)))
                .orElseThrow(() -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public Policy requireCurrentForInquiry(String versionCode, UUID revisionId) {
        Policy current = publicCurrent();
        if (revisionId != null && !revisionId.equals(current.id())
                || versionCode == null || !versionCode.equals(current.versionCode()))
            throw new PublicPrivacyPolicyException("PRIVACY_POLICY_NOT_CURRENT");
        return current;
    }

    @Transactional
    public Policy createDraft(UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONSENT_WRITE");
        String scope = actor + ":PUBLIC_PRIVACY_POLICY_DRAFT_CREATE";
        String hash = digest("POST:/api/admin/public-privacy-policies/drafts");
        Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return repository.findById(claim.resourceId()).orElseThrow(
                () -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
        if (repository.findAll().stream().anyMatch(policy -> "DRAFT".equals(policy.status())))
            throw new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_DRAFT_EXISTS");
        Policy published = repository.findPublished().orElseThrow(
                () -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
        int revision = repository.nextRevision();
        LocalDate today = LocalDate.now(clock);
        Write copy = new Write(0, "privacy-" + YearMonth.from(today) + "-r" + revision,
                published.title(), published.collectionItems(), published.purpose(), published.retentionMonths(),
                published.retentionAnchor(), published.contactEmail(), today);
        UUID id = repository.insertDraft(copy, actor, published.id());
        audit(actor, meta, "PUBLIC_PRIVACY_POLICY_DRAFT_CREATED", id, Map.of("basedOnRevision", published.revision()));
        repository.complete(scope, key, id, 201);
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public Policy updateDraft(UUID id, Write raw, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONSENT_WRITE");
        Write write = normalize(raw);
        Policy current = repository.findByIdForUpdate(id).filter(policy -> "DRAFT".equals(policy.status()))
                .orElseThrow(() -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
        if (write.version() != current.version()) throw new PublicPrivacyPolicyException("PRIVACY_POLICY_VERSION_CONFLICT");
        if (!write.versionCode().equals(current.versionCode())) throw new PublicPrivacyPolicyException("VALIDATION_ERROR");
        if (repository.updateDraft(id, write) != 1) throw new PublicPrivacyPolicyException("PRIVACY_POLICY_VERSION_CONFLICT");
        audit(actor, meta, "PUBLIC_PRIVACY_POLICY_DRAFT_UPDATED", id, Map.of("revision", current.revision()));
        return repository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id, Authentication auth) {
        require(auth, "CONSENT_READ");
        Policy draft = repository.findById(id).filter(policy -> "DRAFT".equals(policy.status()))
                .orElseThrow(() -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
        Policy published = repository.findPublished().orElse(null);
        boolean mismatch = retentionMismatch(draft);
        boolean consentReview = published == null || !draft.collectionItems().equals(published.collectionItems())
                || !draft.purpose().equals(published.purpose()) || draft.retentionMonths() != published.retentionMonths()
                || !draft.retentionAnchor().equals(published.retentionAnchor());
        return new Preview(draft, published, INTERNAL_RETENTION_MONTHS, INTERNAL_RETENTION_ANCHOR, mismatch, consentReview);
    }

    @Transactional
    public Policy publish(UUID id, PublicationRequest request, UUID key, Authentication auth, RequestMetadata meta) {
        UUID actor = require(auth, "CONSENT_WRITE");
        if (request == null || key == null || request.version() < 0)
            throw new PublicPrivacyPolicyException("VALIDATION_ERROR");
        String scope = actor + ":PUBLIC_PRIVACY_POLICY_PUBLISH:" + id;
        String hash = digest("POST:/api/admin/public-privacy-policies/" + id + "/publication:" + request);
        Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return repository.findById(claim.resourceId()).orElseThrow(
                () -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
        Policy draft = repository.findByIdForUpdate(id).filter(policy -> "DRAFT".equals(policy.status()))
                .orElseThrow(() -> new PublicPrivacyPolicyException("PUBLIC_PRIVACY_POLICY_NOT_FOUND"));
        if (draft.version() != request.version()) throw new PublicPrivacyPolicyException("PRIVACY_POLICY_VERSION_CONFLICT");
        if (draft.effectiveOn().isAfter(LocalDate.now(clock)))
            throw new PublicPrivacyPolicyException("PRIVACY_POLICY_FUTURE_EFFECTIVE_DATE");
        boolean mismatch = retentionMismatch(draft);
        if (mismatch && !request.retentionMismatchReviewed())
            throw new PublicPrivacyPolicyException("PRIVACY_POLICY_RETENTION_REVIEW_REQUIRED");
        repository.publish(id, draft.version(), actor);
        audit(actor, meta, "PUBLIC_PRIVACY_POLICY_PUBLISHED", id,
                Map.of("revision", draft.revision(), "retentionMismatchReviewed", mismatch));
        repository.complete(scope, key, id, 201);
        return repository.findById(id).orElseThrow();
    }

    private static boolean retentionMismatch(Policy policy) {
        return policy.retentionMonths() != INTERNAL_RETENTION_MONTHS
                || !INTERNAL_RETENTION_ANCHOR.equals(policy.retentionAnchor());
    }

    private Write normalize(Write raw) {
        if (raw == null) throw new PublicPrivacyPolicyException("VALIDATION_ERROR");
        String code = trim(raw.versionCode());
        String title = trim(raw.title());
        String items = trim(raw.collectionItems());
        String purpose = trim(raw.purpose());
        String anchor = raw.retentionAnchor() == null ? null : raw.retentionAnchor().trim().toUpperCase(Locale.ROOT);
        String email = raw.contactEmail() == null ? null : raw.contactEmail().trim().toLowerCase(Locale.ROOT);
        if (code == null || !code.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,29}")
                || title == null || title.isEmpty() || title.length() > 200
                || items == null || items.isEmpty() || items.length() > 4000
                || purpose == null || purpose.isEmpty() || purpose.length() > 4000
                || raw.retentionMonths() < 1 || raw.retentionMonths() > 120
                || !RETENTION_ANCHORS.contains(anchor)
                || email == null || email.length() > 254
                || !email.matches("(?i)^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$")
                || raw.effectiveOn() == null || raw.effectiveOn().isAfter(LocalDate.now(clock)))
            throw new PublicPrivacyPolicyException("VALIDATION_ERROR");
        return new Write(raw.version(), code, title, items, purpose, raw.retentionMonths(), anchor, email, raw.effectiveOn());
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }
    private void audit(UUID actor, RequestMetadata meta, String action, UUID id, Map<String,Object> details) {
        audit.record(new Event(clock.instant(), meta.requestId(), "MGT-PUBLIC-PRIVACY-POLICY", "OPERATION",
                "ADMIN", actor, null, action, "PUBLIC_PRIVACY_POLICY", id, "SUCCESS", null,
                meta.ipAddress(), meta.userAgent(), details));
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static UUID require(Authentication auth, String permission) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> permission.equals(a.getAuthority())))
            throw new PublicPrivacyPolicyException(permission + "_DENIED");
        try { return UUID.fromString(auth.getName()); }
        catch (RuntimeException exception) { throw new PublicPrivacyPolicyException(permission + "_DENIED"); }
    }
}
