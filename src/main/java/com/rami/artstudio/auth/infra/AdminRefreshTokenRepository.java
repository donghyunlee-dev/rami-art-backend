package com.rami.artstudio.auth.infra;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rami.artstudio.auth.domain.AdminRefreshToken;

public interface AdminRefreshTokenRepository extends JpaRepository<AdminRefreshToken, UUID> {

    Optional<AdminRefreshToken> findByRefreshTokenAndRevokedFalse(String refreshToken);

    @Modifying
    @Transactional
    @Query("update AdminRefreshToken t set t.revoked = true where t.adminUser.id = :adminUserId and t.revoked = false")
    int revokeAllByAdminUserId(@Param("adminUserId") UUID adminUserId);
}
