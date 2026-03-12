package com.rami.artstudio.gallery.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rami.artstudio.common.api.ApiResponse;
import com.rami.artstudio.gallery.dto.GalleryDtos.CategoryView;
import com.rami.artstudio.gallery.dto.GalleryDtos.PageResponse;
import com.rami.artstudio.gallery.dto.GalleryDtos.WorkView;
import com.rami.artstudio.gallery.service.GalleryService;

@RestController
@RequestMapping("/api/v1/gallery")
public class PublicGalleryController {

    private final GalleryService galleryService;

    public PublicGalleryController(GalleryService galleryService) {
        this.galleryService = galleryService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryView>> getCategories() {
        return ApiResponse.success(galleryService.getPublicCategories());
    }

    @GetMapping("/works")
    public ApiResponse<PageResponse<WorkView>> getWorks(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String ageGroup,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(galleryService.getPublicWorks(categoryId, ageGroup, featured, keyword, page, size));
    }

    @GetMapping("/works/{workId}")
    public ApiResponse<WorkView> getWork(@PathVariable UUID workId) {
        return ApiResponse.success(galleryService.getPublicWork(workId));
    }
}
