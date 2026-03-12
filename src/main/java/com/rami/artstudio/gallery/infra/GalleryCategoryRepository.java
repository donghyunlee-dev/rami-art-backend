package com.rami.artstudio.gallery.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rami.artstudio.gallery.domain.GalleryCategory;

public interface GalleryCategoryRepository extends JpaRepository<GalleryCategory, UUID> {
    List<GalleryCategory> findAllByIsActiveTrueOrderBySortOrderAscCreatedAtAsc();

    List<GalleryCategory> findAllByOrderBySortOrderAscCreatedAtAsc();

    Optional<GalleryCategory> findByIdAndIsActiveTrue(UUID id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);
}
