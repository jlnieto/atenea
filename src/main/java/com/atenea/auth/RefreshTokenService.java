package com.atenea.auth;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRefreshTokenEntity;
import com.atenea.persistence.auth.OperatorRefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private final OperatorRefreshTokenRepository refreshTokenRepository;
    private final OperatorAuthProperties properties;

    public RefreshTokenService(
            OperatorRefreshTokenRepository refreshTokenRepository,
            OperatorAuthProperties properties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
    }

    @Transactional
    public IssuedRefreshToken createRefreshToken(OperatorEntity operator) {
        purgeExpiredTokens();
        Instant now = Instant.now();
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        OperatorRefreshTokenEntity refreshToken = new OperatorRefreshTokenEntity();
        refreshToken.setOperator(operator);
        refreshToken.setTokenHash(hash(rawToken));
        refreshToken.setExpiresAt(now.plus(properties.getJwt().getRefreshTokenTtl()));
        refreshToken.setCreatedAt(now);
        refreshToken.setUpdatedAt(now);
        refreshTokenRepository.save(refreshToken);
        return new IssuedRefreshToken(rawToken, refreshToken.getExpiresAt());
    }

    @Transactional
    public OperatorEntity consumeRefreshToken(String rawToken) {
        purgeExpiredTokens();
        OperatorRefreshTokenEntity token = getValidToken(rawToken);
        Instant now = Instant.now();
        token.setLastUsedAt(now);
        token.setRevokedAt(now);
        token.setUpdatedAt(now);
        refreshTokenRepository.save(token);
        return token.getOperator();
    }

    @Transactional
    public void revokeRefreshToken(String rawToken) {
        Optional<OperatorRefreshTokenEntity> existing = refreshTokenRepository.findByTokenHash(hash(rawToken));
        if (existing.isEmpty()) {
            return;
        }
        OperatorRefreshTokenEntity token = existing.get();
        if (token.getRevokedAt() == null) {
            Instant now = Instant.now();
            token.setRevokedAt(now);
            token.setUpdatedAt(now);
            refreshTokenRepository.save(token);
        }
    }

    private OperatorRefreshTokenEntity getValidToken(String rawToken) {
        OperatorRefreshTokenEntity token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new OperatorAuthenticationException("Invalid refresh token"));

        if (token.getRevokedAt() != null) {
            throw new OperatorAuthenticationException("Refresh token already revoked");
        }
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            throw new OperatorAuthenticationException("Refresh token expired");
        }
        if (!token.getOperator().isActive()) {
            throw new OperatorAuthenticationException("Operator account is inactive");
        }
        return token;
    }

    private void purgeExpiredTokens() {
        refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash refresh token", exception);
        }
    }

    public record IssuedRefreshToken(
            String token,
            Instant expiresAt
    ) {
    }
}
