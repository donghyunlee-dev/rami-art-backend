package com.rami.artstudio.gallery.dto;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class GalleryDtos {

    private GalleryDtos() {
    }

    public record CategoryView(
            String id,
            String name,
            String slug,
            Integer sortOrder,
            Boolean isActive,
            OffsetDateTime createdAt) {
    }

    public record CategorySummary(
            String id,
            String name,
            String slug) {
    }

    public record WorkView(
            String id,
            String title,
            String description,
            String imageUrl,
            String imagePath,
            String ageGroup,
            String artistName,
            Integer createdYear,
            Integer sortOrder,
            Boolean isFeatured,
            Boolean isActive,
            CategorySummary category,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record PageResponse<T>(
            List<T> content,
            long totalElements,
            int totalPages,
            int number,
            int size) {
    }

    public record UpsertCategoryRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean isActive) {
    }

    public record UpsertWorkRequest(
            String categoryId,
            @NotBlank @Size(max = 255) String title,
            String description,
            @Pattern(regexp = "kindergarten|elementary|middle") String ageGroup,
            @Size(max = 100) String artistName,
            @Min(1900) @Max(2100) Integer createdYear,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean isFeatured,
            @NotNull Boolean isActive) {
    }

    public record UpdateWorkSortRequest(
            @NotNull @Min(0) @Max(9999) Integer sortOrder) {
    }
}
