package com.ramiart.admin.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ramiart.admin.media.application.MediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SupabaseMediaStorageTest {
    @Test
    void storesAndDeletesMediaInPublicBucketAndReturnsStablePublicUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MediaStorage storage = new SupabaseMediaStorage(builder, "https://project.supabase.co", "service-secret", "pub_rami_art_bucket");
        String key = "public-media/2026/10/asset-id.jpg";

        server.expect(requestTo("https://project.supabase.co/storage/v1/object/pub_rami_art_bucket/public-media/2026/10/asset-id.jpg"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "service-secret"))
                .andExpect(header("x-upsert", "false"))
                .andExpect(header("Content-Type", "image/jpeg"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://project.supabase.co/storage/v1/object/pub_rami_art_bucket"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", "service-secret"))
                .andExpect(content().json("{\"prefixes\":[\"public-media/2026/10/asset-id.jpg\"]}"))
                .andRespond(withSuccess());

        storage.store(key, new byte[] {1, 2, 3}, "image/jpeg");
        assertThat(storage.publicUrl(key)).isEqualTo("https://project.supabase.co/storage/v1/object/public/pub_rami_art_bucket/public-media/2026/10/asset-id.jpg");
        storage.delete(key);
        server.verify();
    }
}
