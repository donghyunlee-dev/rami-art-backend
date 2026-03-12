package com.rami.artstudio.gallery.service;

import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import com.rami.artstudio.gallery.dto.GalleryDtos.CategoryView;
import com.rami.artstudio.gallery.dto.GalleryDtos.PageResponse;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpdateWorkSortRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpsertCategoryRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpsertWorkRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.WorkView;

public interface GalleryService {
    List<CategoryView> getPublicCategories();

    List<CategoryView> getAdminCategories();

    CategoryView createCategory(UpsertCategoryRequest request);

    CategoryView updateCategory(UUID categoryId, UpsertCategoryRequest request);

    void deleteCategory(UUID categoryId);

    PageResponse<WorkView> getPublicWorks(UUID categoryId, String ageGroup, Boolean featured, String keyword, int page, int size);

    WorkView getPublicWork(UUID workId);

    PageResponse<WorkView> getAdminWorks(UUID categoryId, String ageGroup, Boolean featured, Boolean isActive, String keyword,
            int page, int size);

    WorkView createWork(UpsertWorkRequest request, MultipartFile imageFile);

    WorkView updateWork(UUID workId, UpsertWorkRequest request, MultipartFile imageFile);

    void deleteWork(UUID workId);

    WorkView updateWorkSortOrder(UUID workId, UpdateWorkSortRequest request);
}
