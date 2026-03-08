package com.rami.artstudio.auth.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rami.artstudio.auth.domain.AdminRefreshToken;
import com.rami.artstudio.auth.domain.AdminUser;
import com.rami.artstudio.auth.dto.AuthDtos.AdminSummary;
import com.rami.artstudio.auth.dto.AuthDtos.ChangePasswordRequest;
import com.rami.artstudio.auth.dto.AuthDtos.LoginRequest;
import com.rami.artstudio.auth.dto.AuthDtos.LoginResponse;
import com.rami.artstudio.auth.dto.AuthDtos.RefreshResponse;
import com.rami.artstudio.auth.infra.AdminRefreshTokenRepository;
import com.rami.artstudio.auth.infra.AdminUserRepository;
import com.rami.artstudio.common.config.AppSecurityProperties;
import com.rami.artstudio.security.JwtTokenProvider;

@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private final AdminUserRepository adminUserRepository;
    private final AdminRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AppSecurityProperties securityProperties;

    public AuthServiceImpl(
            AdminUserRepository adminUserRepository,
            AdminRefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            AppSecurityProperties securityProperties) {
        this.adminUserRepository = adminUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.securityProperties = securityProperties;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        AdminUser adminUser = adminUserRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (adminUser.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), adminUser.getPasswordHash())) {
            throw new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        refreshTokenRepository.revokeAllByAdminUserId(adminUser.getId());

        String accessToken = jwtTokenProvider.createAccessToken(adminUser);
        String refreshToken = generateAndPersistRefreshToken(adminUser);

        adminUser.setLastLoginAt(OffsetDateTime.now());
        adminUserRepository.save(adminUser);

        return new LoginResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.accessTokenExpiresIn(),
                toAdminSummary(adminUser));
    }

    @Override
    public void logout(UUID adminUserId) {
        refreshTokenRepository.revokeAllByAdminUserId(adminUserId);
    }

    @Override
    public RefreshResponse refresh(String refreshToken) {
        AdminRefreshToken saved = refreshTokenRepository.findByRefreshTokenAndRevokedFalse(refreshToken)
                .orElseThrow(() -> new BadCredentialsException("유효하지 않은 리프레시 토큰입니다."));

        if (saved.getExpiresAt().isBefore(OffsetDateTime.now())) {
            saved.setRevoked(true);
            refreshTokenRepository.save(saved);
            throw new BadCredentialsException("만료된 리프레시 토큰입니다.");
        }

        AdminUser adminUser = saved.getAdminUser();
        saved.setRevoked(true);
        refreshTokenRepository.save(saved);

        String newAccessToken = jwtTokenProvider.createAccessToken(adminUser);
        String newRefreshToken = generateAndPersistRefreshToken(adminUser);

        return new RefreshResponse(newAccessToken, newRefreshToken, jwtTokenProvider.accessTokenExpiresIn());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminSummary me(UUID adminUserId) {
        AdminUser adminUser = adminUserRepository.findById(adminUserId)
                .orElseThrow(() -> new BadCredentialsException("관리자 정보를 찾을 수 없습니다."));
        return toAdminSummary(adminUser);
    }

    @Override
    public void changePassword(UUID adminUserId, ChangePasswordRequest request) {
        AdminUser adminUser = adminUserRepository.findById(adminUserId)
                .orElseThrow(() -> new BadCredentialsException("관리자 정보를 찾을 수 없습니다."));

        if (adminUser.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), adminUser.getPasswordHash())) {
            throw new BadCredentialsException("현재 비밀번호가 올바르지 않습니다.");
        }

        adminUser.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        adminUserRepository.save(adminUser);
        refreshTokenRepository.revokeAllByAdminUserId(adminUser.getId());
    }

    private String generateAndPersistRefreshToken(AdminUser adminUser) {
        String refreshToken = UUID.randomUUID().toString() + UUID.randomUUID();

        AdminRefreshToken token = new AdminRefreshToken();
        token.setId(UUID.randomUUID());
        token.setAdminUser(adminUser);
        token.setRefreshToken(refreshToken);
        token.setExpiresAt(OffsetDateTime.now().plusSeconds(securityProperties.getRefreshTokenExpirationSeconds()));
        token.setRevoked(false);
        token.setCreatedAt(OffsetDateTime.now());
        refreshTokenRepository.save(token);

        return refreshToken;
    }

    private AdminSummary toAdminSummary(AdminUser adminUser) {
        return new AdminSummary(
                adminUser.getId().toString(),
                adminUser.getName(),
                adminUser.getRole(),
                adminUser.getEmail());
    }
}
