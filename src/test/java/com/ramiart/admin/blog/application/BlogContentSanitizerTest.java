package com.ramiart.admin.blog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BlogContentSanitizerTest {
    private final BlogContentSanitizer sanitizer = new BlogContentSanitizer();

    @Test
    void canonicalizesAllowedMarkupAndRejectsUnsafeElementsAndUrls() {
        assertThat(sanitizer.sanitize("<p><strong>Hi</strong> <a href=\"https://example.com\">there</a></p>"))
                .isEqualTo("<p><strong>Hi</strong> <a href=\"https://example.com\">there</a></p>");
        assertThatThrownBy(() -> sanitizer.sanitize("<p onclick=\"x()\">Hi</p>"))
                .isInstanceOf(BlogException.class)
                .hasMessage("BLOG_CONTENT_INVALID");
        assertThatThrownBy(() -> sanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>"))
                .isInstanceOf(BlogException.class)
                .hasMessage("BLOG_CONTENT_INVALID");
    }

    @Test
    void measuresUnicodeCodePointsAfterSanitizing() {
        assertThatThrownBy(() -> sanitizer.sanitize("😀".repeat(100_001)))
                .isInstanceOf(BlogException.class)
                .hasMessage("BLOG_CONTENT_TOO_LARGE");
        assertThat(sanitizer.sanitize("😀".repeat(50_000))).hasSize(100_000);
    }
}
