package com.atenea.auth;

import com.atenea.auth.session.SessionInventoryProjection;
import com.atenea.auth.session.SessionVersions;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

        SessionProtocolNegotiation negotiation = negotiate(
                request.sessionProtocolVersion(), request.singleFlightRefresh());
        if (!negotiation.familyProtocol() && refreshTokenService.sessionEnforcementEnabled()) {
            throw new OperatorAuthenticationException("Legacy login is no longer accepted");
        }
        RefreshTokenService.IssuedSession session = negotiation.familyProtocol()
                ? refreshTokenService.createFamilySession(
                        operator, request.clientType(), request.deviceLabel())
                : refreshTokenService.createLegacySession(operator);
        return issueSession(session);
    }

    public MobileAuthSessionResponse refresh(MobileRefreshTokenRequest request) {
        SessionProtocolNegotiation negotiation = negotiate(
                request.sessionProtocolVersion(), request.singleFlightRefresh());
        return issueSession(refreshTokenService.rotateRefreshToken(
                request.refreshToken().trim(),
                negotiation,
                request.clientType(),
                request.deviceLabel()));
    }

    @Transactional
    public MobileAuthSessionResponse loginWithWebAuthn(
            AuthenticatedOperator authenticatedOperator,
            String sessionProtocolVersion,
            Boolean singleFlightRefresh,
            String clientType,
            String deviceLabel
    ) {
        SessionProtocolNegotiation negotiation = negotiate(
                sessionProtocolVersion, singleFlightRefresh);
        if (!negotiation.familyProtocol()) {
            throw new OperatorAuthenticationException(
                    "WebAuthn login requires session protocol negotiation");
        }
        OperatorEntity operator = operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException(
                        "Operator account not found"));
        return issueSession(refreshTokenService.createFamilySession(
                operator,
                clientType,
                deviceLabel,
                Instant.now(),
                List.of("webauthn")));
    }

    public void logout(MobileLogoutRequest request) {
        refreshTokenService.revokeRefreshToken(
                request.refreshToken().trim(),
                negotiate(request.sessionProtocolVersion(), request.singleFlightRefresh()));
    }

    @Transactional(readOnly = true)
    public List<SessionInventoryProjection> listSessions(
            AuthenticatedOperator authenticatedOperator,
            UUID currentFamilyId
    ) {
        return refreshTokenService.listSessions(
                authenticatedOperator.operatorId(),
                currentFamilyId);
    }

    public void revokeSession(
            AuthenticatedOperator authenticatedOperator,
            UUID familyId
    ) {
        refreshTokenService.revokeSession(authenticatedOperator.operatorId(), familyId);
    }

    public void revokeCurrentSession(
            AuthenticatedOperator authenticatedOperator,
            UUID currentFamilyId
    ) {
        refreshTokenService.revokeCurrentSession(
                authenticatedOperator.operatorId(), currentFamilyId);
    }

    public void revokeAllOtherSessions(
            AuthenticatedOperator authenticatedOperator,
            UUID currentFamilyId
    ) {
        refreshTokenService.revokeAllOtherSessions(
                authenticatedOperator.operatorId(), currentFamilyId);
    }

    public void revokeAllSessions(AuthenticatedOperator authenticatedOperator) {
        refreshTokenService.revokeAllSessions(authenticatedOperator.operatorId());
    }

    @Transactional(readOnly = true)
    public OperatorProfileResponse getCurrentOperator(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
        return toProfile(operator);
    }

    @Transactional(readOnly = true)
    public AuthenticatedSession authenticateAccessToken(String token) {
        JwtTokenService.ParsedAccessToken parsedToken = jwtTokenService.parseSessionAccessToken(token);
        AuthenticatedOperator tokenOperator = parsedToken.operator();
        OperatorEntity operator = operatorRepository.findById(tokenOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
        if (parsedToken.sessionBound()) {
            SessionVersions issuedVersions = new SessionVersions(
                    parsedToken.credentialVersion(), parsedToken.roleVersion());
            if (!issuedVersions.matches(
                    operator.getCredentialVersion(), operator.getRoleVersion())) {
                throw new OperatorAuthenticationException("Access token session version is stale");
            }
            if (!refreshTokenService.isActiveSession(
                    operator.getId(), parsedToken.sessionFamilyId())) {
                throw new OperatorAuthenticationException("Access token session is inactive");
            }
        } else if (refreshTokenService.sessionEnforcementEnabled()) {
            throw new OperatorAuthenticationException("Legacy access token is no longer accepted");
        }
        return new AuthenticatedSession(
                new AuthenticatedOperator(
                        operator.getId(),
                        operator.getEmail(),
                        operator.getDisplayName()),
                parsedToken.sessionFamilyId(),
                parsedToken.authenticatedAt(),
                parsedToken.authenticationMethods());
    }

    private MobileAuthSessionResponse issueSession(RefreshTokenService.IssuedSession session) {
        OperatorEntity operator = session.operator();
        AuthenticatedOperator authenticatedOperator =
                new AuthenticatedOperator(
                        operator.getId(),
                        operator.getEmail(),
                        operator.getDisplayName());
        JwtTokenService.IssuedToken accessToken = session.familyId() == null
                ? jwtTokenService.issueAccessToken(authenticatedOperator)
                : jwtTokenService.issueAccessToken(
                        authenticatedOperator,
                        session.familyId(),
                        new SessionVersions(
                                operator.getCredentialVersion(), operator.getRoleVersion()),
                        session.authenticatedAt(),
                        session.authenticationMethods());
        return new MobileAuthSessionResponse(
                accessToken.token(),
                accessToken.expiresAt(),
                session.refreshToken(),
                session.refreshTokenExpiresAt(),
                toProfile(operator)
        );
    }

    private OperatorProfileResponse toProfile(OperatorEntity operator) {
        return new OperatorProfileResponse(operator.getId(), operator.getEmail(), operator.getDisplayName(),
                operator.getCodexOperationsRole().name());
    }

    private SessionProtocolNegotiation negotiate(
            String requestedVersion,
            Boolean singleFlightRefresh
    ) {
        return SessionProtocolNegotiation.resolve(
                requestedVersion,
                singleFlightRefresh,
                refreshTokenService.supportedProtocolVersion());
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
