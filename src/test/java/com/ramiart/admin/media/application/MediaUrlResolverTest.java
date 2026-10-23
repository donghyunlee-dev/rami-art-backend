package com.ramiart.admin.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaUrlResolverTest {
    private final MediaUrlResolver resolver = new MediaUrlResolver("https://rami-art-backend.onrender.com/");

    @Test
    void resolvesStoredRelativeMediaPathsAgainstTheBackendOrigin() {
        assertThat(resolver.resolve("/media/2026/10/artwork.jpg"))
                .isEqualTo("https://rami-art-backend.onrender.com/media/2026/10/artwork.jpg");
    }

    @Test
    void preservesAlreadyAbsoluteMediaUrls() {
        assertThat(resolver.resolve("https://cdn.example.test/artwork.jpg"))
                .isEqualTo("https://cdn.example.test/artwork.jpg");
    }
}
