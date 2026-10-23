package com.ramiart.admin.galleryartwork.application;

import static com.ramiart.admin.galleryartwork.application.GalleryArtworkModels.Stored;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AdminReauthenticationService;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.media.application.MediaUrlResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class GalleryArtworkDetailModeTest {
    @Test
    void publishedModeReturnsThePublishedRevisionMediaWhenADraftExists() {
        GalleryArtworkRepository repository = mock(GalleryArtworkRepository.class);
        UUID artworkId = UUID.randomUUID();
        UUID draftMediaId = UUID.randomUUID();
        UUID publishedMediaId = UUID.randomUUID();
        when(repository.find(artworkId, "DRAFT")).thenReturn(Optional.of(stored(artworkId, "DRAFT", draftMediaId)));
        when(repository.find(artworkId, "PUBLISHED")).thenReturn(Optional.of(stored(artworkId, "PUBLISHED", publishedMediaId)));
        GalleryArtworkService service = new GalleryArtworkService(repository, mock(AdminReauthenticationService.class),
                mock(AuditRecorder.class), Clock.systemUTC(), new ObjectMapper(),
                new MediaUrlResolver("https://rami-art-backend.onrender.com"));
        var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "",
                java.util.List.of(new SimpleGrantedAuthority("GALLERY_READ")));

        var result = service.detail(artworkId, "published", auth);

        assertThat(result.published().mediaAssetId()).isEqualTo(publishedMediaId);
        assertThat(result.media().id()).isEqualTo(publishedMediaId);
    }

    @Test
    void detailResolvesStoredRelativeMediaPathLikeClassProgramDetail() {
        GalleryArtworkRepository repository = mock(GalleryArtworkRepository.class);
        UUID artworkId = UUID.randomUUID();
        Stored draft = stored(artworkId, "DRAFT", UUID.randomUUID());
        draft = new Stored(draft.id(), draft.artworkId(), draft.revision(), draft.status(), draft.visible(), draft.title(),
                draft.courseId(), draft.courseCode(), draft.courseName(), draft.courseActive(), draft.audienceLabel(),
                draft.medium(), draft.description(), draft.mediaAssetId(), "/media/2026/10/artwork.jpg", draft.fileName(),
                draft.mimeType(), draft.fileSize(), draft.width(), draft.height(), draft.expiresAt(), draft.altText(),
                draft.consentId(), draft.consentStatus(), draft.maskedStudentLabel(), draft.policyRevision(),
                draft.exemptionReason(), draft.featured(), draft.featuredOrder(), draft.version(), draft.updatedAt(),
                draft.publishedAt());
        when(repository.find(artworkId, "DRAFT")).thenReturn(Optional.of(draft));
        GalleryArtworkService service = new GalleryArtworkService(repository, mock(AdminReauthenticationService.class),
                mock(AuditRecorder.class), Clock.systemUTC(), new ObjectMapper(),
                new MediaUrlResolver("https://rami-art-backend.onrender.com"));
        var auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), "",
                java.util.List.of(new SimpleGrantedAuthority("GALLERY_READ")));

        var result = service.detail(artworkId, "draft", auth);

        assertThat(result.media().publicUrl())
                .isEqualTo("https://rami-art-backend.onrender.com/media/2026/10/artwork.jpg");
    }

    private static Stored stored(UUID artworkId, String status, UUID mediaId) {
        return new Stored(UUID.randomUUID(), artworkId, 1, status, true, "작품", UUID.randomUUID(), "KID", "유치부", true,
                "유치부", "수채화", "설명", mediaId, "https://example.com/" + mediaId, "작품.jpg", "image/jpeg", 123,
                100, 100, Instant.parse("2026-10-22T00:00:00Z"), "대체 텍스트", null, null, null, null,
                null, false, null, 1, Instant.parse("2026-10-22T00:00:00Z"), null);
    }
}
