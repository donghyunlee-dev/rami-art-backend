package com.rami.artstudio.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.storage")
public class AppStorageProperties {

    private String supabaseUrl;
    private String serviceKey;
    private String galleryBucket = "pub_rami_art_bucket";

    public String getSupabaseUrl() {
        return supabaseUrl;
    }

    public void setSupabaseUrl(String supabaseUrl) {
        this.supabaseUrl = supabaseUrl;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getGalleryBucket() {
        return galleryBucket;
    }

    public void setGalleryBucket(String galleryBucket) {
        this.galleryBucket = galleryBucket;
    }
}
