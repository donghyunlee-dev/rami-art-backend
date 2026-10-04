package com.ramiart.admin.sitebrand.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.sitebrand.application.SiteBrandModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteBrandService {
    public record RequestMetadata(String requestId, String ipAddress, String userAgent) {}
    private static final List<String> PRIMARY = List.of("#1F2937", "#1D4ED8", "#166534", "#7C2D12", "#6B21A8");
    private static final List<String> ACCENT = List.of("#F59E0B", "#0D9488", "#DB2777", "#2563EB", "#DC2626");
    private static final List<String> FONTS = List.of("SYSTEM_SANS", "SERIF_CLASSIC", "ROUNDED_SANS");
    private final SiteBrandRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;
    public SiteBrandService(SiteBrandRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository; this.audit = audit; this.clock = clock;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ) public AdminView get() {
        RevisionView published = repository.findByStatus("PUBLISHED").orElse(null);
        RevisionView draft = repository.findByStatus("DRAFT").orElse(null);
        return new AdminView(draft != null, draft == null ? null : draft.id(), draft == null ? null : "DRAFT", published, draft);
    }
    @Transactional public RevisionView createDraft(UUID actor, UUID key, RequestMetadata meta) {
        String scope = actor + ":SITE_BRAND_DRAFT_CREATE"; String hash = digest("POST:/api/admin/site-brand/draft");
        SiteBrandRepository.Claim claim = repository.claim(scope, key, hash);
        if (!claim.claimed()) return repository.findById(claim.resourceId()).orElseThrow(() -> new SiteBrandException("SITE_BRAND_NOT_FOUND"));
        if (repository.findByStatus("DRAFT").isPresent()) throw new SiteBrandException("SITE_BRAND_DRAFT_EXISTS");
        RevisionView base = repository.findByStatus("PUBLISHED").orElse(null);
        UUID basedOn = base == null ? null : base.id();
        BrandWrite copied = base == null ? placeholder() : from(base);
        UUID id = repository.insertDraft(copied, actor, basedOn);
        repository.replaceDraftReferences(id, copied);
        record(actor, meta, "SITE_BRAND_DRAFT_CREATED", id, Map.of("basedOnId", basedOn == null ? "" : basedOn.toString()));
        repository.complete(scope, key, id, 201);
        return repository.findById(id).orElseThrow();
    }
    @Transactional public RevisionView updateDraft(UUID id, BrandWrite raw, UUID actor, UUID key, RequestMetadata meta) {
        BrandWrite write = normalize(raw); validate(write);
        String scope = actor + ":SITE_BRAND_DRAFT_UPDATE:" + id;
        SiteBrandRepository.Claim claim = repository.claim(scope, key, digest("PUT:/api/admin/site-brand/draft/" + id + ":" + write));
        if (!claim.claimed()) return repository.findById(id).orElseThrow(() -> new SiteBrandException("SITE_BRAND_NOT_FOUND"));
        RevisionView current = repository.findById(id).filter(v -> "DRAFT".equals(v.status())).orElseThrow(() -> new SiteBrandException("SITE_BRAND_NOT_FOUND"));
        if (raw.version() == null || raw.version() != current.version()) throw new SiteBrandException("SITE_BRAND_VERSION_CONFLICT");
        if (repository.updateDraft(id, write) != 1) throw new SiteBrandException("SITE_BRAND_VERSION_CONFLICT");
        repository.replaceDraftReferences(id, write);
        record(actor, meta, "SITE_BRAND_DRAFT_UPDATED", id, Map.of("revision", current.revision()));
        repository.complete(scope, key, id, 200);
        return repository.findById(id).orElseThrow();
    }
    @Transactional(readOnly = true) public Preview preview(BrandWrite raw) {
        BrandWrite write = normalize(raw); validate(write);
        double primary = contrast(write.primaryColor(), "#FFFFFF");
        List<ContrastCheck> checks = List.of(new ContrastCheck("primary-on-background", primary, primary >= 4.5));
        if (!repository.faviconSquare(write.faviconAssetId())) throw new SiteBrandException("SITE_BRAND_MEDIA_NOT_READY");
        String logo = repository.mediaUrl(write.logoAssetId());
        String favicon = repository.mediaUrl(write.faviconAssetId());
        String share = write.shareAssetId() == null ? null : repository.mediaUrl(write.shareAssetId());
        return new Preview(new RenderModel(write.brandName(), write.shortName(), logo, favicon, share,
                write.primaryColor(), write.accentColor(), write.fontPreset(), write.defaultTitle(),
                write.defaultDescription(), write.canonicalHost()), checks, List.of(), checks.stream().allMatch(ContrastCheck::passed));
    }
    @Transactional public RevisionView publish(UUID id, long version, UUID actor, UUID key, RequestMetadata meta) {
        String scope = actor + ":SITE_BRAND_PUBLISH:" + id;
        SiteBrandRepository.Claim claim = repository.claim(scope, key, digest("POST:/api/admin/site-brand/draft/" + id + "/publish:" + version));
        if (!claim.claimed()) return repository.findById(claim.resourceId()).orElseThrow(() -> new SiteBrandException("SITE_BRAND_NOT_FOUND"));
        RevisionView draft = repository.findById(id).filter(v -> "DRAFT".equals(v.status())).orElseThrow(() -> new SiteBrandException("SITE_BRAND_NOT_FOUND"));
        if (draft.version() != version) throw new SiteBrandException("SITE_BRAND_VERSION_CONFLICT");
        BrandWrite write = from(draft); validate(write);
        if (!preview(write).publishable()) throw new SiteBrandException("SITE_BRAND_CONTRAST_FAILED");
        repository.publish(id, version, actor);
        record(actor, meta, "SITE_BRAND_PUBLISHED", id, Map.of("revision", draft.revision()));
        repository.complete(scope, key, id, 200);
        return repository.findById(id).orElseThrow();
    }
    @Transactional(readOnly = true) public PublicBrand publicBrand() {
        return repository.findPublic().orElseThrow(() -> new SiteBrandException("SITE_BRAND_NOT_FOUND"));
    }
    private void record(UUID actor, RequestMetadata meta, String action, UUID id, Map<String,Object> details) {
        audit.record(new Event(clock.instant(), meta.requestId(), "MGT-SITE-BRAND", "OPERATION", "ADMIN", actor,
                null, action, "SITE_BRAND_CONFIG", id, "SUCCESS", null, meta.ipAddress(), meta.userAgent(), details));
    }
    private static BrandWrite normalize(BrandWrite w) {
        if (w == null) throw new SiteBrandException("SITE_BRAND_VALIDATION_FAILED");
        return new BrandWrite(w.version(), trim(w.brandName()), trim(w.shortName()), w.logoAssetId(), trim(w.logoAltText()),
                w.faviconAssetId(), w.shareAssetId(), w.primaryColor(), w.accentColor(), w.fontPreset(),
                w.canonicalHost(), trim(w.defaultTitle()), trim(w.defaultDescription()), trimNull(w.instagramUrl()), trimNull(w.blogUrl()));
    }
    private static void validate(BrandWrite w) {
        if (!length(w.brandName(),1,100) || !length(w.shortName(),1,30) || !length(w.logoAltText(),1,300)
                || !length(w.defaultTitle(),1,60) || !length(w.defaultDescription(),1,160)
                || w.logoAssetId() == null || w.faviconAssetId() == null || !PRIMARY.contains(w.primaryColor())
                || !ACCENT.contains(w.accentColor()) || !FONTS.contains(w.fontPreset()) || !validHost(w.canonicalHost())
                || !validUrl(w.instagramUrl()) || !validUrl(w.blogUrl())) throw new SiteBrandException("SITE_BRAND_VALIDATION_FAILED");
    }
    private static boolean validHost(String value) { return value != null && value.matches("https://[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?") && value.length() <= 253; }
    private static boolean validUrl(String value) { return value == null || value.length() <= 500 && value.matches("https://[^/?#\\s]+(?:/[^\\s]*)?"); }
    private static String trim(String v) { return v == null ? null : v.trim(); }
    private static String trimNull(String v) { String s = trim(v); return s == null || s.isEmpty() ? null : s; }
    private static boolean length(String s,int min,int max) { return s != null && s.length() >= min && s.length() <= max; }
    private static BrandWrite from(RevisionView r) { return new BrandWrite(r.version(),r.brandName(),r.shortName(),r.logoAssetId(),r.logoAltText(),r.faviconAssetId(),r.shareAssetId(),r.primaryColor(),r.accentColor(),r.fontPreset(),r.canonicalHost(),r.defaultTitle(),r.defaultDescription(),r.instagramUrl(),r.blogUrl()); }
    private static BrandWrite placeholder() { return new BrandWrite(0L,"Studio","Studio",new UUID(0,0),"Studio logo",new UUID(0,0),null,"#1F2937","#F59E0B","SYSTEM_SANS","https://example.com","Studio","Studio website",null,null); }
    private static String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static double contrast(String fg,String bg) { return (luminance(bg)+0.05)/(luminance(fg)+0.05); }
    private static double luminance(String color) { int r=Integer.parseInt(color.substring(1,3),16),g=Integer.parseInt(color.substring(3,5),16),b=Integer.parseInt(color.substring(5,7),16); return .2126*channel(r)+.7152*channel(g)+.0722*channel(b); }
    private static double channel(int c) { double v=c/255.0; return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4); }
}
