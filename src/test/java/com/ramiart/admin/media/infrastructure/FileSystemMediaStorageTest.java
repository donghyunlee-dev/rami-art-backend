package com.ramiart.admin.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FileSystemMediaStorageTest {
    @Test
    void rejectsFilesystemFallbackWhenDurableStorageIsRequired() {
        assertThatThrownBy(() -> new FileSystemMediaStorage("build/test-media-storage", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Persistent media storage is required");
    }

    @Test
    void permitsFilesystemStorageForLocalDevelopment() {
        assertThat(new FileSystemMediaStorage("build/test-media-storage", false)).isNotNull();
    }
}
