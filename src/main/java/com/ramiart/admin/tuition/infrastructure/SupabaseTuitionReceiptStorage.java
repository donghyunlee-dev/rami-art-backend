package com.ramiart.admin.tuition.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ramiart.admin.tuition.application.TuitionReceiptStorage;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
public final class SupabaseTuitionReceiptStorage implements TuitionReceiptStorage {
    private final RestClient client;
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;
    public SupabaseTuitionReceiptStorage(RestClient.Builder builder,
            @Value("${app.storage.supabase-url:}") String baseUrl,
            @Value("${app.storage.service-key:}") String serviceKey,
            @Value("${app.storage.tuition-receipt-bucket:rami_tuition_receipts_private}") String bucket) {
        this.client=builder.build();this.baseUrl=baseUrl==null?"":baseUrl.replaceAll("/+$","");this.serviceKey=serviceKey;this.bucket=bucket;
    }
    @Override public void upload(String key,byte[] pdf){checkConfiguration();try{client.post().uri(objectUri(key)).contentType(MediaType.APPLICATION_PDF)
            .header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey).header("x-upsert","false").body(pdf).retrieve().toBodilessEntity();}
        catch(RuntimeException e){throw new JdbcTuitionReceiptRepository.TuitionReceiptException("RECEIPT_GENERATION_FAILED",e);}}
    @Override public byte[] download(String key){checkConfiguration();try{return client.get().uri(objectUri(key)).header("apikey",serviceKey)
            .header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey).retrieve().body(byte[].class);}
        catch(RuntimeException e){throw new JdbcTuitionReceiptRepository.TuitionReceiptException("RECEIPT_FILE_INTEGRITY_FAILED",e);}}
    @Override public String signedUrl(String key,int seconds){checkConfiguration();try{SignedPath signed=client.post().uri(baseUrl+"/storage/v1/object/sign/"+UriUtils.encodePathSegment(bucket,java.nio.charset.StandardCharsets.UTF_8)+"/"+encodedPath(key))
            .contentType(MediaType.APPLICATION_JSON).header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey)
            .body(new SignRequest(seconds)).retrieve().body(SignedPath.class);if(signed==null||!StringUtils.hasText(signed.signedUrl()))throw new IllegalStateException();
            return signed.signedUrl().startsWith("http")?signed.signedUrl():baseUrl+"/storage/v1"+signed.signedUrl();}
        catch(RuntimeException e){throw new JdbcTuitionReceiptRepository.TuitionReceiptException("RECEIPT_FILE_INTEGRITY_FAILED",e);}}
    @Override public void delete(String key){checkConfiguration();try{client.delete().uri(objectUri(key)).header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey).retrieve().toBodilessEntity();}
        catch(RuntimeException ignored){/* Storage reconciliation can remove an orphan after database rollback. */}}
    private URI objectUri(String key){return URI.create(baseUrl+"/storage/v1/object/"+UriUtils.encodePathSegment(bucket,java.nio.charset.StandardCharsets.UTF_8)+"/"+encodedPath(key));}
    private static String encodedPath(String key){return java.util.Arrays.stream(key.split("/",-1)).map(segment->UriUtils.encodePathSegment(segment,java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("/"));}
    private void checkConfiguration(){if(!StringUtils.hasText(baseUrl)||!StringUtils.hasText(serviceKey)||!StringUtils.hasText(bucket))throw new JdbcTuitionReceiptRepository.TuitionReceiptException("RECEIPT_STORAGE_UNAVAILABLE");}
    private record SignRequest(@JsonProperty("expiresIn") int expiresIn){}
    private record SignedPath(@JsonProperty("signedURL") String signedUrl){}
}
