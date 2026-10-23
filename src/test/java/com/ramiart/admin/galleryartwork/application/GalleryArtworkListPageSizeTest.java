package com.ramiart.admin.galleryartwork.application;

import static com.ramiart.admin.galleryartwork.application.GalleryArtworkModels.PageInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AdminReauthenticationService;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.media.application.MediaUrlResolver;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class GalleryArtworkListPageSizeTest {
    @Test
    void acceptsEightItemsPerPageForTheGalleryCardGrid() {
        GalleryArtworkRepository repository = mock(GalleryArtworkRepository.class);
        when(repository.list(null, null, null, "ALL", "UPDATED_DESC", 0, 8))
                .thenReturn(new GalleryArtworkModels.Page(List.of(), new PageInfo(0, 8, 0, 0)));
        GalleryArtworkService service = new GalleryArtworkService(repository,
                mock(AdminReauthenticationService.class), mock(AuditRecorder.class), Clock.systemUTC(),
                new ObjectMapper(), new MediaUrlResolver("https://rami-art-backend.onrender.com"));
        var auth = new UsernamePasswordAuthenticationToken("93000000-0000-0000-0000-000000000001", "",
                List.of(new SimpleGrantedAuthority("GALLERY_READ")));

        var result = service.list(null, null, null, "ALL", "UPDATED_DESC", 0, 8, auth);

        assertThat(result.page().size()).isEqualTo(8);
    }
}
