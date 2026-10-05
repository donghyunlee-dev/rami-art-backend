package com.ramiart.admin.datatransfer.infrastructure;

import com.ramiart.admin.datatransfer.application.DataTransferStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
public final class SupabaseDataTransferStorage implements DataTransferStorage {
    private final RestClient client;
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;

    public SupabaseDataTransferStorage(RestClient.Builder builder,
            @Value("${app.storage.supabase-url:}") String baseUrl,
            @Value("${app.storage.service-key:}") String serviceKey,
            @Value("${app.storage.data-transfer-bucket:rami_data_transfers_private}") String bucket) {
        this.client = builder.build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.serviceKey = serviceKey;
        this.bucket = bucket;
    }

    @Override public void upload(String key, byte[] file) {
        check();
        try {
            client.post().uri(uri(key)).contentType(MediaType.parseMediaType("text/csv"))
                    .header("apikey", serviceKey).header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                    .header("x-upsert", "false").body(file).retrieve().toBodilessEntity();
        } catch (RuntimeException exception) { throw new StorageUnavailable(exception); }
    }

    @Override public void delete(String key) {
        check();
        try {
            client.delete().uri(uri(key)).header("apikey", serviceKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
        } catch (RuntimeException exception) { throw new StorageUnavailable(exception); }
    }

    @Override public String createSignedUrl(String key, int expiresInSeconds) {
        check();
        try {
            var response = client.post().uri(baseUrl + "/storage/v1/object/sign/"
                            + UriUtils.encodePathSegment(bucket, StandardCharsets.UTF_8) + "/" + encodedKey(key))
                    .contentType(MediaType.APPLICATION_JSON).header("apikey", serviceKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                    .body(java.util.Map.of("expiresIn", expiresInSeconds)).retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, String>>() {});
            String signed = response == null ? null : response.get("signedURL");
            if (!StringUtils.hasText(signed)) throw new StorageUnavailable(null);
            return signed.startsWith("http") ? signed : baseUrl + "/storage/v1" + signed;
        } catch (RuntimeException exception) { throw new StorageUnavailable(exception); }
    }

    private URI uri(String key) {
        String path = encodedKey(key);
        return URI.create(baseUrl + "/storage/v1/object/"
                + UriUtils.encodePathSegment(bucket, StandardCharsets.UTF_8) + "/" + path);
    }

    private static String encodedKey(String key) {
        return java.util.Arrays.stream(key.split("/", -1))
                .map(part -> UriUtils.encodePathSegment(part, StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private void check() {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(serviceKey) || !StringUtils.hasText(bucket))
            throw new StorageUnavailable(null);
    }

    public static final class StorageUnavailable extends RuntimeException {
        public StorageUnavailable(Throwable cause) { super("DATA_TRANSFER_STORAGE_UNAVAILABLE", cause); }
    }
}
