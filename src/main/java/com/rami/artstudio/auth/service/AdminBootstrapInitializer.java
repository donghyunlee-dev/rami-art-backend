package com.rami.artstudio.auth.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rami.artstudio.auth.domain.AdminUser;
import com.rami.artstudio.auth.infra.AdminUserRepository;
import com.rami.artstudio.common.config.AppSecurityProperties;

@Component
public class AdminBootstrapInitializer implements ApplicationRunner {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppSecurityProperties securityProperties;

    public AdminBootstrapInitializer(
            AdminUserRepository adminUserRepository,
            PasswordEncoder passwordEncoder,
            AppSecurityProperties securityProperties) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        String email = securityProperties.getBootstrapAdminEmail();
        adminUserRepository.findByEmail(email).ifPresentOrElse(admin -> {
            if (admin.getPasswordHash() == null || admin.getPasswordHash().isBlank()) {
                admin.setPasswordHash(passwordEncoder.encode(securityProperties.getBootstrapAdminPassword()));
                adminUserRepository.save(admin);
            }
        }, () -> {
            AdminUser admin = new AdminUser();
            admin.setId(UUID.randomUUID());
            admin.setName(securityProperties.getBootstrapAdminName());
            admin.setEmail(email);
            admin.setRole("admin");
            admin.setPasswordHash(passwordEncoder.encode(securityProperties.getBootstrapAdminPassword()));
            admin.setCreatedAt(OffsetDateTime.now());
            admin.setUpdatedAt(OffsetDateTime.now());
            adminUserRepository.save(admin);
        });
    }
}
