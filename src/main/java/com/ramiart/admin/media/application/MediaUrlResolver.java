package com.ramiart.admin.media.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class MediaUrlResolver {
    private final String publicBaseUrl;

    public MediaUrlResolver(@Value("${admin.media.public-base-url:https://rami-art-backend.onrender.com}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public String resolve(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) return mediaUrl;
        if (mediaUrl.startsWith("https://") || mediaUrl.startsWith("http://")) return mediaUrl;
        return publicBaseUrl + (mediaUrl.startsWith("/") ? mediaUrl : "/" + mediaUrl);
    }
}
