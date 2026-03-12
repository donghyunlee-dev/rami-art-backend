package com.rami.artstudio.gallery.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rami.artstudio.common.api.ApiResponse;
import com.rami.artstudio.gallery.dto.GalleryDtos.CategoryView;
import com.rami.artstudio.gallery.dto.GalleryDtos.PageResponse;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpdateWorkSortRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpsertCategoryRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.UpsertWorkRequest;
import com.rami.artstudio.gallery.dto.GalleryDtos.WorkView;
import com.rami.artstudio.gallery.service.GalleryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/gallery")
public class AdminGalleryController {

    private final GalleryService galleryService;

    public AdminGalleryController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryView>> getCategories() {
        return ApiResponse.success(galleryService.getAdminCategories());
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<CategoryView>> createCategory(@Valid @RequestBody UpsertCategoryRequest request) {
        CategoryView created = galleryService.createCategory(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/gallery/categories/" + created.id()))
                .body(ApiResponse.success(created, "갤러리 카테고리가 생성되었습니다."));
    }

    @PutMapping("/categories/{categoryId}")
    public ApiResponse<CategoryView> updateCategory(@PathVariable UUID categoryId, @Valid @RequestBody UpsertCategoryRequest request) {
        return ApiResponse.success(galleryService.updateCategory(categoryId, request), "갤러리 카테고리가 수정되었습니다.");
    }

    @DeleteMapping("/categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable UUID categoryId) {
        galleryService.deleteCategory(categoryId);
        return ApiResponse.success(null, "갤러리 카테고리가 삭제되었습니다.");
    }

    @GetMapping("/works")
    public ApiResponse<PageResponse<WorkView>> getWorks(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String ageGroup,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(galleryService.getAdminWorks(categoryId, ageGroup, featured, isActive, keyword, page, size));
    }

    @PostMapping(value = "/works", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<WorkView>> createWork(
            @Valid @ModelAttribute UpsertWorkRequest request,
            @RequestParam("image") MultipartFile image) {
        WorkView created = galleryService.createWork(request, image);
        return ResponseEntity.created(URI.create("/api/v1/admin/gallery/works/" + created.id()))
                .body(ApiResponse.success(created, "갤러리 작품이 생성되었습니다."));
    }

    @PutMapping(value = "/works/{workId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<WorkView> updateWork(
            @PathVariable UUID workId,
            @Valid @ModelAttribute UpsertWorkRequest request,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        return ApiResponse.success(galleryService.updateWork(workId, request, image), "갤러리 작품이 수정되었습니다.");
    }

    @DeleteMapping("/works/{workId}")
    public ApiResponse<Void> deleteWork(@PathVariable UUID workId) {
        galleryService.deleteWork(workId);
        return ApiResponse.success(null, "갤러리 작품이 삭제되었습니다.");
    }

    @PatchMapping("/works/{workId}/sort")
    public ApiResponse<WorkView> updateWorkSortOrder(
            @PathVariable UUID workId,
            @Valid @RequestBody UpdateWorkSortRequest request) {
        return ApiResponse.success(galleryService.updateWorkSortOrder(workId, request), "정렬 순서가 변경되었습니다.");
    }
}
