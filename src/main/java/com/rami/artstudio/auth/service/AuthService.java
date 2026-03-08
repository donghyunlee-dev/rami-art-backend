package com.rami.artstudio.auth.service;

import java.util.UUID;

import com.rami.artstudio.auth.dto.AuthDtos.AdminSummary;
import com.rami.artstudio.auth.dto.AuthDtos.ChangePasswordRequest;
import com.rami.artstudio.auth.dto.AuthDtos.LoginRequest;
import com.rami.artstudio.auth.dto.AuthDtos.LoginResponse;
import com.rami.artstudio.auth.dto.AuthDtos.RefreshResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);

    void logout(UUID adminUserId);

    RefreshResponse refresh(String refreshToken);

    AdminSummary me(UUID adminUserId);

    void changePassword(UUID adminUserId, ChangePasswordRequest request);
}
