package com.ramiart.admin.media.infrastructure;

import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
@ConditionalOnExpression("'${app.storage.supabase-url:}' != '' and '${app.storage.service-key:}' != ''")
public final class SupabaseMediaStorage implements MediaStorage {
    private static final String KEY_PREFIX = "public-media/";
    private static final Logger LOGGER = LoggerFactory.getLogger(SupabaseMediaStorage.class);

    private final RestClient client;
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;

    public SupabaseMediaStorage(RestClient.Builder builder,
            @Value("${app.storage.supabase-url:}") String baseUrl,
            @Value("${app.storage.service-key:}") String serviceKey,
            @Value("${app.storage.gallery-bucket:pub_rami_art_bucket}") String bucket) {
        this.client = builder.build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
        this.bucket = bucket;
        LOGGER.info("media_storage_backend=supabase");
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        check(key);
        try {
            client.post().uri(objectUrl(key)).contentType(MediaType.parseMediaType(contentType))
                    .header("apikey", serviceKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                    .header("x-upsert", "false").body(content).retrieve().toBodilessEntity();
        } catch (RuntimeException exception) {
            throw new MediaException("MEDIA_STORAGE_FAILED", exception);
        }
    }

    @Override
    public void delete(String key) {
        check(key);
        try {
            client.delete().uri(objectUrl(key)).header("apikey", serviceKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
            // A missing object is already in the requested deleted state.
        } catch (RuntimeException exception) {
            throw new MediaException("MEDIA_STORAGE_FAILED", exception);
        }
    }

    @Override
    public String publicUrl(String key) {
        check(key);
        return baseUrl + "/storage/v1/object/public/" + encode(bucket) + "/" + encodePath(key);
    }

    private URI objectUrl(String key) {
        return URI.create(baseUrl + "/storage/v1/object/" + encode(bucket) + "/" + encodePath(key));
    }

    private void check(String key) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(serviceKey) || !StringUtils.hasText(bucket)
                || key == null || !key.startsWith(KEY_PREFIX) || key.contains("..")) {
            throw new MediaException("MEDIA_STORAGE_FAILED");
        }
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/", -1)).map(SupabaseMediaStorage::encode)
                .collect(java.util.stream.Collectors.joining("/"));
    }
}
