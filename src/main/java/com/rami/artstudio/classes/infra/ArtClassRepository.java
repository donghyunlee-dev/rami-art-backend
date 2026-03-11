package com.rami.artstudio.classes.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rami.artstudio.classes.domain.ArtClass;

public interface ArtClassRepository extends JpaRepository<ArtClass, UUID> {
    List<ArtClass> findAllByIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();

    List<ArtClass> findAllByDeletedAtIsNullOrderBySortOrderAscCreatedAtAsc();

    Optional<ArtClass> findByIdAndIsActiveTrueAndDeletedAtIsNull(UUID id);

    Optional<ArtClass> findByIdAndDeletedAtIsNull(UUID id);

    Optional<ArtClass> findByAgeGroupAndDeletedAtIsNull(String ageGroup);

    boolean existsByAgeGroupAndDeletedAtIsNull(String ageGroup);

    boolean existsByAgeGroupAndDeletedAtIsNullAndIdNot(String ageGroup, UUID id);
}
