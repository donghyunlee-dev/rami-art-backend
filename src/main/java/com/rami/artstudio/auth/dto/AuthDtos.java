package com.rami.artstudio.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 64) String newPassword) {
    }

    public record AdminSummary(
            String id,
            String name,
            String role,
            String email) {
    }

    public record LoginResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            AdminSummary admin) {
    }

    public record RefreshResponse(
            String accessToken,
            String refreshToken,
            long expiresIn) {
    }
}
