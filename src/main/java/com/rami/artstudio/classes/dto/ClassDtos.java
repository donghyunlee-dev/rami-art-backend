package com.rami.artstudio.classes.dto;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ClassDtos {

    private ClassDtos() {
    }

    public record UpsertClassRequest(
            @NotBlank @Pattern(regexp = "kindergarten|elementary|middle") String ageGroup,
            @NotBlank @Size(max = 50) String ageRange,
            @NotBlank @Size(max = 100) String title,
            @NotBlank String description,
            @NotBlank String curriculum,
            String thumbnailUrl,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean isActive,
            @NotEmpty List<@NotBlank @Size(max = 100) String> tags) {
    }

    public record UpdateSortRequest(
            @NotNull @Min(0) @Max(9999) Integer sortOrder) {
    }

    public record ClassView(
            String id,
            String ageGroup,
            String ageRange,
            String title,
            String description,
            String curriculum,
            String thumbnailUrl,
            Integer sortOrder,
            Boolean isActive,
            List<String> tags,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
