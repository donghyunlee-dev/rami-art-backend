package com.rami.artstudio.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rami.artstudio.auth.dto.AuthDtos.AdminSummary;
import com.rami.artstudio.auth.dto.AuthDtos.ChangePasswordRequest;
import com.rami.artstudio.auth.dto.AuthDtos.LoginRequest;
import com.rami.artstudio.auth.dto.AuthDtos.LoginResponse;
import com.rami.artstudio.auth.dto.AuthDtos.RefreshRequest;
import com.rami.artstudio.auth.dto.AuthDtos.RefreshResponse;
import com.rami.artstudio.auth.service.AuthService;
import com.rami.artstudio.common.api.ApiResponse;
import com.rami.artstudio.security.AuthPrincipal;
import com.rami.artstudio.security.SecurityPrincipalResolver;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final SecurityPrincipalResolver principalResolver;

    public AuthController(AuthService authService, SecurityPrincipalResolver principalResolver) {
        this.authService = authService;
        this.principalResolver = principalResolver;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(request.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        AuthPrincipal principal = principalResolver.currentPrincipal();
        authService.logout(principal.adminUserId());
        return ResponseEntity.ok(ApiResponse.success(null, "로그아웃 되었습니다."));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AdminSummary>> me() {
        AuthPrincipal principal = principalResolver.currentPrincipal();
        return ResponseEntity.ok(ApiResponse.success(authService.me(principal.adminUserId())));
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        AuthPrincipal principal = principalResolver.currentPrincipal();
        authService.changePassword(principal.adminUserId(), request);
        return ResponseEntity.ok(ApiResponse.success(null, "비밀번호가 변경되었습니다."));
    }
}
