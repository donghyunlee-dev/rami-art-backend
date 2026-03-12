package com.rami.artstudio.gallery.infra;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.rami.artstudio.gallery.domain.GalleryWork;

public interface GalleryWorkRepository extends JpaRepository<GalleryWork, UUID>, JpaSpecificationExecutor<GalleryWork> {
    Optional<GalleryWork> findByIdAndIsActiveTrueAndDeletedAtIsNull(UUID id);

    Optional<GalleryWork> findByIdAndDeletedAtIsNull(UUID id);
}
