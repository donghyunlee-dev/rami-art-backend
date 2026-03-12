package com.rami.artstudio.gallery.storage;

import java.io.IOException;
import java.net.URI;
import java.time.Year;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.rami.artstudio.common.config.AppStorageProperties;

@Component
public class SupabaseGalleryImageStorage implements GalleryImageStorage {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/webp");

    private final RestClient restClient;
    private final AppStorageProperties storageProperties;

    public SupabaseGalleryImageStorage(RestClient.Builder restClientBuilder, AppStorageProperties storageProperties) {
        this.restClient = restClientBuilder.build();
        this.storageProperties = storageProperties;
    }

    @Override
    public UploadedImage upload(MultipartFile file) {
        validateConfigured();
        validateFile(file);

        String extension = resolveExtension(file);
        String imagePath = "gallery/" + Year.now().getValue() + "/" + UUID.randomUUID() + "." + extension;

        try {
            restClient.post()
                    .uri(buildObjectUri(imagePath))
                    .contentType(MediaType.parseMediaType(file.getContentType()))
                    .header("apikey", storageProperties.getServiceKey())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + storageProperties.getServiceKey())
                    .header("x-upsert", "false")
                    .body(file.getBytes())
                    .retrieve()
                    .toBodilessEntity();
        } catch (IOException ex) {
            throw new IllegalStateException("이미지 파일을 읽는 중 오류가 발생했습니다.", ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("이미지 업로드에 실패했습니다.", ex);
        }

        return new UploadedImage(buildPublicUrl(imagePath), imagePath);
    }

    @Override
    public void delete(String imagePath) {
        validateConfigured();
        if (!StringUtils.hasText(imagePath)) {
            return;
        }

        try {
            restClient.delete()
                    .uri(buildObjectUri(imagePath))
                    .header("apikey", storageProperties.getServiceKey())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + storageProperties.getServiceKey())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("기존 이미지 삭제에 실패했습니다.", ex);
        }
    }

    private void validateConfigured() {
        if (!StringUtils.hasText(storageProperties.getSupabaseUrl()) || !StringUtils.hasText(storageProperties.getServiceKey())) {
            throw new IllegalStateException("Supabase Storage 설정이 누락되었습니다.");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지 파일은 필수입니다.");
        }
        if (!StringUtils.hasText(file.getContentType()) || !ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("이미지는 JPEG, PNG, WebP 형식만 업로드할 수 있습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("이미지 파일은 10MB 이하여야 합니다.");
        }
    }

    private String resolveExtension(MultipartFile file) {
        return switch (file.getContentType()) {
            case MediaType.IMAGE_JPEG_VALUE -> "jpg";
            case MediaType.IMAGE_PNG_VALUE -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다.");
        };
    }

    private URI buildObjectUri(String imagePath) {
        String baseUrl = trimTrailingSlash(storageProperties.getSupabaseUrl());
        return URI.create(baseUrl + "/storage/v1/object/" + storageProperties.getGalleryBucket() + "/" + imagePath);
    }

    private String buildPublicUrl(String imagePath) {
        String baseUrl = trimTrailingSlash(storageProperties.getSupabaseUrl());
        return baseUrl + "/storage/v1/object/public/" + storageProperties.getGalleryBucket() + "/" + imagePath;
    }

    private String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
