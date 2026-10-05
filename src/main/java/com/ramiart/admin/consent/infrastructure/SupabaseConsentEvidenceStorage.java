package com.ramiart.admin.consent.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ramiart.admin.consent.application.ConsentEvidenceStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
public final class SupabaseConsentEvidenceStorage implements ConsentEvidenceStorage {
    private final RestClient client;
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;

    public SupabaseConsentEvidenceStorage(RestClient.Builder builder,@Value("${app.storage.supabase-url:}")String baseUrl,
            @Value("${app.storage.service-key:}")String serviceKey,@Value("${app.storage.consent-evidence-bucket:rami_consent_evidence_private}")String bucket){
        this.client=builder.build();this.baseUrl=baseUrl==null?"":baseUrl.replaceAll("/+$","");this.serviceKey=serviceKey;this.bucket=bucket;
    }

    @Override public String signedUrl(String storageKey,int expiresInSeconds){
        if(!StringUtils.hasText(baseUrl)||!StringUtils.hasText(serviceKey)||!StringUtils.hasText(bucket)||expiresInSeconds<1||expiresInSeconds>300)throw new ConsentEvidenceStorageException();
        try{
            SignedPath result=client.post().uri(baseUrl+"/storage/v1/object/sign/"+UriUtils.encodePathSegment(bucket,StandardCharsets.UTF_8)+"/"+encodePath(storageKey))
                    .contentType(MediaType.APPLICATION_JSON).header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey)
                    .body(new SignRequest(expiresInSeconds)).retrieve().body(SignedPath.class);
            if(result==null||!StringUtils.hasText(result.signedUrl()))throw new IllegalStateException();
            return result.signedUrl().startsWith("http")?result.signedUrl():baseUrl+"/storage/v1"+result.signedUrl();
        }catch(RuntimeException exception){throw new ConsentEvidenceStorageException();}
    }

    private static String encodePath(String key){return java.util.Arrays.stream(key.split("/",-1)).map(part->UriUtils.encodePathSegment(part,StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("/"));}
    private record SignRequest(@JsonProperty("expiresIn")int expiresIn){}
    private record SignedPath(@JsonProperty("signedURL")String signedUrl){}
    public static final class ConsentEvidenceStorageException extends RuntimeException{public ConsentEvidenceStorageException(){super("CONSENT_EVIDENCE_STORAGE_UNAVAILABLE");}}
}
