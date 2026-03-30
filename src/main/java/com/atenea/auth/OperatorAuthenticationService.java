package com.atenea.auth;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorAuthenticationService {

    private final OperatorRepository operatorRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    public OperatorAuthenticationService(
            OperatorRepository operatorRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService
    ) {
        this.operatorRepository = operatorRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public MobileAuthSessionResponse login(MobileLoginRequest request) {
        OperatorEntity operator = operatorRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Invalid operator credentials"));

        if (!passwordEncoder.matches(request.password(), operator.getPasswordHash())) {
            throw new OperatorAuthenticationException("Invalid operator credentials");
        }

        return issueSession(operator);
    }

    @Transactional
    public MobileAuthSessionResponse refresh(MobileRefreshTokenRequest request) {
        OperatorEntity operator = refreshTokenService.consumeRefreshToken(request.refreshToken().trim());
        return issueSession(operator);
    }

    @Transactional
    public void logout(MobileLogoutRequest request) {
        refreshTokenService.revokeRefreshToken(request.refreshToken().trim());
    }

    @Transactional(readOnly = true)
    public OperatorProfileResponse getCurrentOperator(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
        return toProfile(operator);
    }

    @Transactional(readOnly = true)
    public AuthenticatedOperator authenticateAccessToken(String token) {
        AuthenticatedOperator tokenOperator = jwtTokenService.parseAccessToken(token);
        OperatorEntity operator = operatorRepository.findById(tokenOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
        return new AuthenticatedOperator(operator.getId(), operator.getEmail(), operator.getDisplayName());
    }

    private MobileAuthSessionResponse issueSession(OperatorEntity operator) {
        AuthenticatedOperator authenticatedOperator =
                new AuthenticatedOperator(operator.getId(), operator.getEmail(), operator.getDisplayName());
        JwtTokenService.IssuedToken accessToken = jwtTokenService.issueAccessToken(authenticatedOperator);
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.createRefreshToken(operator);
        return new MobileAuthSessionResponse(
                accessToken.token(),
                accessToken.expiresAt(),
                refreshToken.token(),
                refreshToken.expiresAt(),
                toProfile(operator)
        );
    }

    private OperatorProfileResponse toProfile(OperatorEntity operator) {
        return new OperatorProfileResponse(operator.getId(), operator.getEmail(), operator.getDisplayName());
    }

    @Transactional
    public void bootstrapOperator(String email, String displayName, String rawPassword) {
        if (operatorRepository.findByEmailIgnoreCase(email).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(email.trim().toLowerCase());
        operator.setDisplayName(displayName);
        operator.setPasswordHash(passwordEncoder.encode(rawPassword));
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        operatorRepository.save(operator);
    }
}
