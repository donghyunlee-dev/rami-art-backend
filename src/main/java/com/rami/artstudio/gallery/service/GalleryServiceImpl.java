package com.rami.artstudio.gallery.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.rami.artstudio.common.exception.ConflictException;
import com.rami.artstudio.gallery.domain.GalleryCategory;
import com.rami.artstudio.gallery.domain.GalleryWork;
import com.rami.artstudio.gallery.dto.GalleryDtos.CategorySummary;
import com.rami.artstudio.gallery.dto.GalleryDtos.CategoryView;
import com.rami.artstudio.gallery.dto.GalleryDtos.PageResponse;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpdateWorkSortRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpsertCategoryRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpsertWorkRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.WorkView;
import com.rami.artstudio.gallery.infra.GalleryCategoryRepository;
import com.rami.artstudio.gallery.infra.GalleryWorkRepository;
import com.rami.artstudio.gallery.storage.GalleryImageStorage;
import com.rami.artstudio.gallery.storage.GalleryImageStorage.UploadedImage;

import jakarta.persistence.criteria.Predicate;

@Service
@Transactional
public class GalleryServiceImpl implements GalleryService {

    private final GalleryCategoryRepository categoryRepository;
    private final GalleryWorkRepository workRepository;
    private final GalleryImageStorage galleryImageStorage;

    public GalleryServiceImpl(
            GalleryCategoryRepository categoryRepository,
            GalleryWorkRepository workRepository,
            GalleryImageStorage galleryImageStorage) {
        this.categoryRepository = categoryRepository;
        this.workRepository = workRepository;
        this.galleryImageStorage = galleryImageStorage;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> getPublicCategories() {
        return categoryRepository.findAllByIsActiveTrueOrderBySortOrderAscCreatedAtAsc().stream()
                .map(this::toCategoryView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> getAdminCategories() {
        return categoryRepository.findAllByOrderBySortOrderAscCreatedAtAsc().stream()
                .map(this::toCategoryView)
                .toList();
    }

    @Override
    public CategoryView createCategory(UpsertCategoryRequest request) {
        if (categoryRepository.existsBySlug(request.slug())) {
            throw new ConflictException("같은 slug의 카테고리가 이미 존재합니다.");
        }

        GalleryCategory category = new GalleryCategory();
        category.setId(UUID.randomUUID());
        category.setName(request.name().trim());
        category.setSlug(request.slug().trim());
        category.setSortOrder(request.sortOrder());
        category.setIsActive(request.isActive());
        category.setCreatedAt(OffsetDateTime.now());
        return toCategoryView(categoryRepository.save(category));
    }

    @Override
    public CategoryView updateCategory(UUID categoryId, UpsertCategoryRequest request) {
        GalleryCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NoSuchElementException("갤러리 카테고리를 찾을 수 없습니다."));

        if (categoryRepository.existsBySlugAndIdNot(request.slug(), categoryId)) {
            throw new ConflictException("같은 slug의 카테고리가 이미 존재합니다.");
        }

        category.setName(request.name().trim());
        category.setSlug(request.slug().trim());
        category.setSortOrder(request.sortOrder());
        category.setIsActive(request.isActive());
        return toCategoryView(categoryRepository.save(category));
    }

    @Override
    public void deleteCategory(UUID categoryId) {
        GalleryCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NoSuchElementException("갤러리 카테고리를 찾을 수 없습니다."));
        categoryRepository.delete(category);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkView> getPublicWorks(UUID categoryId, String ageGroup, Boolean featured, String keyword, int page, int size) {
        Page<GalleryWork> works = workRepository.findAll(
                buildWorkSpecification(true, categoryId, ageGroup, featured, null, keyword),
                createPageable(page, size));
        return toPageResponse(works);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkView getPublicWork(UUID workId) {
        GalleryWork work = workRepository.findByIdAndIsActiveTrueAndDeletedAtIsNull(workId)
                .orElseThrow(() -> new NoSuchElementException("갤러리 작품을 찾을 수 없습니다."));
        return toWorkView(work, loadCategorySummaries(List.of(work.getCategoryId())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkView> getAdminWorks(UUID categoryId, String ageGroup, Boolean featured, Boolean isActive, String keyword,
            int page, int size) {
        Page<GalleryWork> works = workRepository.findAll(
                buildWorkSpecification(false, categoryId, ageGroup, featured, isActive, keyword),
                createPageable(page, size));
        return toPageResponse(works);
    }

    @Override
    public WorkView createWork(UpsertWorkRequest request, MultipartFile imageFile) {
        UUID categoryId = parseCategoryId(request.categoryId());
        ensureCategoryExists(categoryId);
        UploadedImage uploadedImage = galleryImageStorage.upload(imageFile);

        OffsetDateTime now = OffsetDateTime.now();
        GalleryWork work = new GalleryWork();
        work.setId(UUID.randomUUID());
        work.setCategoryId(categoryId);
        applyWorkRequest(work, request, uploadedImage);
        work.setCreatedAt(now);
        work.setUpdatedAt(now);
        return toWorkView(workRepository.save(work), loadCategorySummaries(List.of(categoryId)));
    }

    @Override
    public WorkView updateWork(UUID workId, UpsertWorkRequest request, MultipartFile imageFile) {
        GalleryWork work = workRepository.findByIdAndDeletedAtIsNull(workId)
                .orElseThrow(() -> new NoSuchElementException("갤러리 작품을 찾을 수 없습니다."));

        UUID categoryId = parseCategoryId(request.categoryId());
        ensureCategoryExists(categoryId);

        UploadedImage uploadedImage = null;
        String oldImagePath = work.getImagePath();
        if (imageFile != null && !imageFile.isEmpty()) {
            uploadedImage = galleryImageStorage.upload(imageFile);
        }

        work.setCategoryId(categoryId);
        applyWorkRequest(work, request, uploadedImage);
        work.setUpdatedAt(OffsetDateTime.now());
        GalleryWork saved = workRepository.save(work);
        if (uploadedImage != null && StringUtils.hasText(oldImagePath)) {
            galleryImageStorage.delete(oldImagePath);
        }
        return toWorkView(saved, loadCategorySummaries(List.of(categoryId)));
    }

    @Override
    public void deleteWork(UUID workId) {
        GalleryWork work = workRepository.findByIdAndDeletedAtIsNull(workId)
                .orElseThrow(() -> new NoSuchElementException("갤러리 작품을 찾을 수 없습니다."));
        if (StringUtils.hasText(work.getImagePath())) {
            galleryImageStorage.delete(work.getImagePath());
        }
        OffsetDateTime now = OffsetDateTime.now();
        work.setDeletedAt(now);
        work.setUpdatedAt(now);
        workRepository.save(work);
    }

    @Override
    public WorkView updateWorkSortOrder(UUID workId, UpdateWorkSortRequest request) {
        GalleryWork work = workRepository.findByIdAndDeletedAtIsNull(workId)
                .orElseThrow(() -> new NoSuchElementException("갤러리 작품을 찾을 수 없습니다."));
        work.setSortOrder(request.sortOrder());
        work.setUpdatedAt(OffsetDateTime.now());
        return toWorkView(workRepository.save(work), loadCategorySummaries(List.of(work.getCategoryId())));
    }

    private void applyWorkRequest(GalleryWork work, UpsertWorkRequest request, UploadedImage uploadedImage) {
        work.setTitle(request.title().trim());
        work.setDescription(request.description());
        if (uploadedImage != null) {
            work.setImageUrl(uploadedImage.imageUrl());
            work.setImagePath(uploadedImage.imagePath());
        }
        work.setAgeGroup(request.ageGroup());
        work.setArtistName(request.artistName());
        work.setCreatedYear(request.createdYear());
        work.setSortOrder(request.sortOrder());
        work.setIsFeatured(request.isFeatured());
        work.setIsActive(request.isActive());
    }

    private void ensureCategoryExists(UUID categoryId) {
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new NoSuchElementException("갤러리 카테고리를 찾을 수 없습니다.");
        }
    }

    private UUID parseCategoryId(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(categoryId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("categoryId 형식이 올바르지 않습니다.");
        }
    }

    private Pageable createPageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by("sortOrder").ascending().and(Sort.by("createdAt").descending()));
    }

    private Specification<GalleryWork> buildWorkSpecification(
            boolean publicOnly,
            UUID categoryId,
            String ageGroup,
            Boolean featured,
            Boolean isActive,
            String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (publicOnly) {
                predicates.add(cb.isTrue(root.get("isActive")));
            } else if (isActive != null) {
                predicates.add(cb.equal(root.get("isActive"), isActive));
            }

            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (ageGroup != null && !ageGroup.isBlank()) {
                predicates.add(cb.equal(root.get("ageGroup"), ageGroup));
            }
            if (featured != null) {
                predicates.add(cb.equal(root.get("isFeatured"), featured));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("artistName"), "")), like)));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private PageResponse<WorkView> toPageResponse(Page<GalleryWork> works) {
        List<UUID> categoryIds = works.getContent().stream()
                .map(GalleryWork::getCategoryId)
                .toList();
        Map<UUID, CategorySummary> categorySummaries = loadCategorySummaries(categoryIds);
        List<WorkView> content = works.getContent().stream()
                .map(work -> toWorkView(work, categorySummaries))
                .toList();
        return new PageResponse<>(content, works.getTotalElements(), works.getTotalPages(), works.getNumber(), works.getSize());
    }

    private Map<UUID, CategorySummary> loadCategorySummaries(List<UUID> categoryIds) {
        List<UUID> ids = categoryIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        return categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(GalleryCategory::getId, this::toCategorySummary, (left, right) -> left));
    }

    private CategoryView toCategoryView(GalleryCategory category) {
        return new CategoryView(
                category.getId().toString(),
                category.getName(),
                category.getSlug(),
                category.getSortOrder(),
                category.getIsActive(),
                category.getCreatedAt());
    }

    private CategorySummary toCategorySummary(GalleryCategory category) {
        return new CategorySummary(category.getId().toString(), category.getName(), category.getSlug());
    }

    private WorkView toWorkView(GalleryWork work, Map<UUID, CategorySummary> categorySummaries) {
        return new WorkView(
                work.getId().toString(),
                work.getTitle(),
                work.getDescription(),
                work.getImageUrl(),
                work.getImagePath(),
                work.getAgeGroup(),
                work.getArtistName(),
                work.getCreatedYear(),
                work.getSortOrder(),
                work.getIsFeatured(),
                work.getIsActive(),
                categorySummaries.get(work.getCategoryId()),
                work.getCreatedAt(),
                work.getUpdatedAt());
    }
}
