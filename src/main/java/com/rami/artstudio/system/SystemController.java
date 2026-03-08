package com.rami.artstudio.system;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rami.artstudio.common.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final String appName;
    private final String appVersion;

    public SystemController(
            @Value("${spring.application.name}") String appName,
            @Value("${app.version}") String appVersion) {
        this.appName = appName;
        this.appVersion = appVersion;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> data = Map.of(
                "status", "UP",
                "service", appName,
                "timestamp", Instant.now().toString());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/version")
    public ResponseEntity<ApiResponse<Map<String, String>>> version() {
        Map<String, String> data = Map.of(
                "service", appName,
                "version", appVersion);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
