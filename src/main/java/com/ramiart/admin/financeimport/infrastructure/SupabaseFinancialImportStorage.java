package com.ramiart.admin.financeimport.infrastructure;

import com.ramiart.admin.financeimport.application.FinancialImportStorage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

@Component
public final class SupabaseFinancialImportStorage implements FinancialImportStorage {
    private final RestClient client;private final String baseUrl,serviceKey,bucket;
    public SupabaseFinancialImportStorage(RestClient.Builder builder,@Value("${app.storage.supabase-url:}")String baseUrl,
            @Value("${app.storage.service-key:}")String serviceKey,@Value("${app.storage.financial-import-bucket:rami_financial_imports_private}")String bucket){
        this.client=builder.build();this.baseUrl=baseUrl==null?"":baseUrl.replaceAll("/+$","");this.serviceKey=serviceKey;this.bucket=bucket;
    }
    @Override public void upload(String key,byte[] file){check();try{client.post().uri(uri(key)).contentType(MediaType.parseMediaType("text/csv"))
            .header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey).header("x-upsert","false").body(file).retrieve().toBodilessEntity();}
        catch(RuntimeException e){throw new FinancialImportStorageException(e);}}
    @Override public byte[] download(String key){check();try{return client.get().uri(uri(key)).header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey).retrieve().body(byte[].class);}
        catch(RuntimeException e){throw new FinancialImportStorageException(e);}}
    @Override public void delete(String key){check();try{client.delete().uri(uri(key)).header("apikey",serviceKey).header(HttpHeaders.AUTHORIZATION,"Bearer "+serviceKey).retrieve().toBodilessEntity();}
        catch(HttpClientErrorException.NotFound ignored){}catch(RuntimeException e){throw new FinancialImportStorageException(e);}}
    private URI uri(String key){return URI.create(baseUrl+"/storage/v1/object/"+UriUtils.encodePathSegment(bucket,StandardCharsets.UTF_8)+"/"+
            java.util.Arrays.stream(key.split("/",-1)).map(x->UriUtils.encodePathSegment(x,StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("/")));}
    private void check(){if(!StringUtils.hasText(baseUrl)||!StringUtils.hasText(serviceKey)||!StringUtils.hasText(bucket))throw new FinancialImportStorageException(null);}
    public static final class FinancialImportStorageException extends RuntimeException{public FinancialImportStorageException(Throwable cause){super("IMPORT_STORAGE_UNAVAILABLE",cause);}}
}
