package com.atenea.auth;

import com.atenea.auth.session.SessionFamilyState;
import com.atenea.auth.session.SessionInventoryProjection;
import com.atenea.auth.session.SessionInventoryState;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRefreshTokenEntity;
import com.atenea.persistence.auth.OperatorRefreshTokenRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorSessionFamilyEntity;
import com.atenea.persistence.auth.OperatorSessionFamilyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final Pattern CLIENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,23}$");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");
    private static final List<String> PASSWORD_AUTHENTICATION = List.of("pwd");

    private final OperatorRefreshTokenRepository refreshTokenRepository;
    private final OperatorSessionFamilyRepository sessionFamilyRepository;
    private final OperatorRepository operatorRepository;
    private final OperatorAuthProperties properties;

    public RefreshTokenService(
            OperatorRefreshTokenRepository refreshTokenRepository,
            OperatorSessionFamilyRepository sessionFamilyRepository,
            OperatorRepository operatorRepository,
            OperatorAuthProperties properties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionFamilyRepository = sessionFamilyRepository;
        this.operatorRepository = operatorRepository;
        this.properties = properties;
    }

    @Transactional
    public IssuedSession createLegacySession(OperatorEntity operator) {
        purgeExpiredLegacyTokens();
        Instant now = Instant.now();
        IssuedRefreshToken token = createLegacyToken(operator, now);
        return new IssuedSession(
                operator, null, token.token(), token.expiresAt(), now, List.of());
    }

    @Transactional
    public IssuedSession createFamilySession(
            OperatorEntity operator,
            String requestedClientType,
            String requestedDeviceLabel
    ) {
        return createFamilySession(
                operator,
                requestedClientType,
                requestedDeviceLabel,
                Instant.now(),
                PASSWORD_AUTHENTICATION);
    }

    @Transactional
    public IssuedSession createFamilySession(
            OperatorEntity operator,
            String requestedClientType,
            String requestedDeviceLabel,
            Instant authenticatedAt,
            List<String> authenticationMethods
    ) {
        purgeExpiredLegacyTokens();
        Instant now = Instant.now();
        String authenticationMethod = exactAuthenticationMethod(authenticationMethods);
        OperatorSessionFamilyEntity family = createFamily(
                operator,
                requestedClientType,
                requestedDeviceLabel,
                0L,
                now,
                now,
                now.plus(properties.getJwt().getRefreshTokenTtl()),
                authenticatedAt,
                authenticationMethod);
        IssuedRefreshToken token = createFamilyToken(operator, family, 0L, now);
        return familySession(operator, family, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = OperatorAuthenticationException.class)
    public IssuedSession rotateRefreshToken(
            String rawToken,
            SessionProtocolNegotiation negotiation,
            String requestedClientType,
            String requestedDeviceLabel
    ) {
        purgeExpiredLegacyTokens();
        OperatorRefreshTokenEntity token = refreshTokenRepository
                .findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> new OperatorAuthenticationException("Invalid refresh token"));
        if (token.getSessionFamily() == null) {
            if (negotiation.familyProtocol()) {
                return adoptLegacyToken(token, requestedClientType, requestedDeviceLabel);
            }
            if (sessionEnforcementEnabled()) {
                throw new OperatorAuthenticationException("Legacy refresh token is no longer accepted");
            }
            return rotateLegacyToken(token);
        }
        if (!negotiation.familyProtocol()) {
            throw new OperatorAuthenticationException("Session protocol negotiation required");
        }
        assertNoSessionMetadata(requestedClientType, requestedDeviceLabel);
        return rotateFamilyToken(token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeRefreshToken(
            String rawToken,
            SessionProtocolNegotiation negotiation
    ) {
        Optional<OperatorRefreshTokenEntity> existing = refreshTokenRepository
                .findByTokenHashForUpdate(hash(rawToken));
        if (existing.isEmpty()) {
            return;
        }
        OperatorRefreshTokenEntity token = existing.get();
        if (token.getSessionFamily() == null) {
            revokeLegacyToken(token, "LOGOUT");
            return;
        }
        if (!negotiation.familyProtocol()) {
            throw new OperatorAuthenticationException("Session protocol negotiation required");
        }
        OperatorSessionFamilyEntity family = sessionFamilyRepository
                .findByIdForUpdate(token.getSessionFamily().getId())
                .orElseThrow(() -> new OperatorAuthenticationException("Session family not found"));
        revokeFamily(family, "LOGOUT", Instant.now());
    }

    @Transactional(readOnly = true)
    public List<SessionInventoryProjection> listSessions(
            Long operatorId,
            UUID currentFamilyId
    ) {
        Instant now = Instant.now();
        return sessionFamilyRepository
                .findAllByOperatorIdOrderByLastUsedAtDescIdAsc(operatorId)
                .stream()
                .map(family -> new SessionInventoryProjection(
                        family.getId(),
                        family.getClientType(),
                        family.getDeviceLabel(),
                        SessionInventoryProjection.roundToMinute(family.getCreatedAt()),
                        SessionInventoryProjection.roundToMinute(family.getLastUsedAt()),
                        SessionInventoryProjection.roundToMinute(family.getAbsoluteExpiresAt()),
                        inventoryState(family, now),
                        family.getId().equals(currentFamilyId)))
                .toList();
    }

    @Transactional
    public void revokeSession(Long operatorId, UUID familyId) {
        OperatorSessionFamilyEntity family = sessionFamilyRepository
                .findByIdForUpdate(familyId)
                .filter(candidate -> candidate.getOperator().getId().equals(operatorId))
                .orElseThrow(() -> new OperatorAuthenticationException("Session not found"));
        revokeFamily(family, "OPERATOR_REVOKED", Instant.now());
    }

    @Transactional
    public void revokeCurrentSession(Long operatorId, UUID currentFamilyId) {
        if (currentFamilyId == null) {
            throw new OperatorAuthenticationException("Current session is not family-bound");
        }
        revokeSession(operatorId, currentFamilyId);
    }

    @Transactional
    public void revokeAllOtherSessions(Long operatorId, UUID currentFamilyId) {
        if (currentFamilyId == null) {
            throw new OperatorAuthenticationException("Current session is not family-bound");
        }
        List<OperatorSessionFamilyEntity> families =
                sessionFamilyRepository.findAllByOperatorIdForUpdate(operatorId);
        if (families.stream().noneMatch(family -> family.getId().equals(currentFamilyId))) {
            throw new OperatorAuthenticationException("Current session not found");
        }
        Instant now = Instant.now();
        families.stream()
                .filter(family -> !family.getId().equals(currentFamilyId))
                .forEach(family -> revokeFamily(family, "ALL_OTHERS_REVOKED", now));
        refreshTokenRepository.revokeActiveLegacyTokens(
                operatorId, now, "ALL_OTHERS_REVOKED");
    }

    @Transactional
    public void revokeAllSessions(Long operatorId) {
        OperatorEntity operator = operatorRepository.findByIdForUpdate(operatorId)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
        Instant now = Instant.now();
        sessionFamilyRepository.findAllByOperatorIdForUpdate(operatorId)
                .forEach(family -> revokeFamily(family, "ALL_SESSIONS_REVOKED", now));
        refreshTokenRepository.revokeActiveLegacyTokens(
                operatorId, now, "ALL_SESSIONS_REVOKED");
        try {
            operator.setCredentialVersion(Math.addExact(operator.getCredentialVersion(), 1L));
        } catch (ArithmeticException exception) {
            throw new OperatorAuthenticationException("Credential version cannot advance");
        }
        operator.setUpdatedAt(now);
        operatorRepository.saveAndFlush(operator);
    }

    @Transactional(readOnly = true)
    public boolean isActiveSession(Long operatorId, UUID familyId) {
        return sessionFamilyRepository.findByIdAndOperatorId(familyId, operatorId)
                .filter(family -> family.getRevokedAt() == null)
                .filter(family -> family.getAbsoluteExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }

    public boolean sessionEnforcementEnabled() {
        return properties.getSessions().isEnforcementEnabled();
    }

    public String supportedProtocolVersion() {
        return properties.getSessions().getSupportedProtocolVersion();
    }

    private IssuedSession rotateLegacyToken(OperatorRefreshTokenEntity token) {
        assertUsable(token);
        Instant now = Instant.now();
        token.setLastUsedAt(now);
        token.setConsumedAt(now);
        token.setRevokedAt(now);
        token.setRevocationReason("LEGACY_ROTATED");
        token.setUpdatedAt(now);
        refreshTokenRepository.save(token);

        IssuedRefreshToken successor = createLegacyToken(token.getOperator(), now);
        return new IssuedSession(
                token.getOperator(), null, successor.token(), successor.expiresAt(), now, List.of());
    }

    private IssuedSession adoptLegacyToken(
            OperatorRefreshTokenEntity token,
            String requestedClientType,
            String requestedDeviceLabel
    ) {
        assertUsable(token);
        Instant now = Instant.now();
        OperatorSessionFamilyEntity family = createFamily(
                token.getOperator(),
                requestedClientType,
                requestedDeviceLabel,
                1L,
                token.getCreatedAt(),
                now,
                token.getExpiresAt(),
                token.getCreatedAt(),
                "pwd");
        IssuedRefreshToken successor = createFamilyToken(
                token.getOperator(), family, 1L, now);

        token.setSessionFamily(family);
        token.setGeneration(0L);
        token.setLastUsedAt(now);
        token.setConsumedAt(now);
        token.setRevokedAt(now);
        token.setReplacedByTokenId(successor.persistedId());
        token.setRevocationReason("LEGACY_ADOPTED");
        token.setUpdatedAt(now);
        refreshTokenRepository.save(token);
        return familySession(token.getOperator(), family, successor);
    }

    private IssuedSession rotateFamilyToken(OperatorRefreshTokenEntity token) {
        OperatorSessionFamilyEntity family = sessionFamilyRepository
                .findByIdForUpdate(token.getSessionFamily().getId())
                .orElseThrow(() -> new OperatorAuthenticationException("Session family not found"));
        Instant now = Instant.now();

        if (token.getConsumedAt() != null) {
            revokeFamily(family, "REPLAY_DETECTED", now);
            throw new OperatorAuthenticationException("Refresh token replay detected");
        }
        if (token.getRevokedAt() != null) {
            throw new OperatorAuthenticationException("Refresh token already revoked");
        }
        if (family.getRevokedAt() != null) {
            throw new OperatorAuthenticationException("Session is revoked");
        }
        if (!family.getAbsoluteExpiresAt().isAfter(now)) {
            revokeFamily(family, "EXPIRED", now);
            throw new OperatorAuthenticationException("Session expired");
        }
        assertUsable(token);
        if (token.getGeneration() == null) {
            throw new OperatorAuthenticationException("Refresh token has no session generation");
        }

        SessionFamilyState.Transition transition = new SessionFamilyState(
                family.getId(), family.getCurrentGeneration(), false)
                .consume(token.getGeneration());
        if (transition.outcome() == SessionFamilyState.Outcome.REPLAY_REVOKED) {
            revokeFamily(family, "REPLAY_DETECTED", now);
            throw new OperatorAuthenticationException("Refresh token replay detected");
        }
        if (transition.outcome() != SessionFamilyState.Outcome.ROTATED) {
            throw new OperatorAuthenticationException("Invalid refresh token generation");
        }

        long successorGeneration = transition.successorGeneration();
        IssuedRefreshToken successor = createFamilyToken(
                token.getOperator(), family, successorGeneration, now);
        token.setLastUsedAt(now);
        token.setConsumedAt(now);
        token.setRevokedAt(now);
        token.setReplacedByTokenId(successor.persistedId());
        token.setRevocationReason("ROTATED");
        token.setUpdatedAt(now);
        refreshTokenRepository.save(token);
        family.setCurrentGeneration(successorGeneration);
        family.setLastUsedAt(now);
        sessionFamilyRepository.save(family);
        return familySession(token.getOperator(), family, successor);
    }

    private OperatorSessionFamilyEntity createFamily(
            OperatorEntity operator,
            String requestedClientType,
            String requestedDeviceLabel,
            long currentGeneration,
            Instant createdAt,
            Instant lastUsedAt,
            Instant absoluteExpiresAt,
            Instant authenticatedAt,
            String authenticationMethod
    ) {
        String clientType = defaultIfBlank(
                requestedClientType, properties.getSessions().getDefaultClientType());
        String deviceLabel = defaultIfBlank(
                requestedDeviceLabel, properties.getSessions().getDefaultDeviceLabel());
        validateMetadata(clientType, deviceLabel);

        OperatorSessionFamilyEntity family = new OperatorSessionFamilyEntity();
        family.setId(UUID.randomUUID());
        family.setOperator(operator);
        family.setClientType(clientType);
        family.setDeviceLabel(deviceLabel);
        family.setCurrentGeneration(currentGeneration);
        family.setCreatedAt(createdAt);
        family.setLastUsedAt(lastUsedAt);
        family.setAbsoluteExpiresAt(absoluteExpiresAt);
        family.setAuthenticatedAt(authenticatedAt);
        family.setAuthenticationMethod(authenticationMethod);
        return sessionFamilyRepository.save(family);
    }

    private IssuedRefreshToken createLegacyToken(OperatorEntity operator, Instant now) {
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        OperatorRefreshTokenEntity refreshToken = new OperatorRefreshTokenEntity();
        refreshToken.setOperator(operator);
        refreshToken.setTokenHash(hash(rawToken));
        refreshToken.setExpiresAt(now.plus(properties.getJwt().getRefreshTokenTtl()));
        refreshToken.setCreatedAt(now);
        refreshToken.setUpdatedAt(now);
        refreshTokenRepository.saveAndFlush(refreshToken);
        return new IssuedRefreshToken(
                refreshToken.getId(), rawToken, refreshToken.getExpiresAt());
    }

    private IssuedRefreshToken createFamilyToken(
            OperatorEntity operator,
            OperatorSessionFamilyEntity family,
            long generation,
            Instant now
    ) {
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        OperatorRefreshTokenEntity refreshToken = new OperatorRefreshTokenEntity();
        refreshToken.setOperator(operator);
        refreshToken.setTokenHash(hash(rawToken));
        refreshToken.setExpiresAt(family.getAbsoluteExpiresAt());
        refreshToken.setSessionFamily(family);
        refreshToken.setGeneration(generation);
        refreshToken.setCreatedAt(now);
        refreshToken.setUpdatedAt(now);
        refreshTokenRepository.saveAndFlush(refreshToken);
        return new IssuedRefreshToken(
                refreshToken.getId(), rawToken, refreshToken.getExpiresAt());
    }

    private IssuedSession familySession(
            OperatorEntity operator,
            OperatorSessionFamilyEntity family,
            IssuedRefreshToken token
    ) {
        return new IssuedSession(
                operator,
                family.getId(),
                token.token(),
                token.expiresAt(),
                family.getAuthenticatedAt() == null
                        ? family.getCreatedAt()
                        : family.getAuthenticatedAt(),
                family.getAuthenticationMethod() == null
                        ? PASSWORD_AUTHENTICATION
                        : List.of(family.getAuthenticationMethod()));
    }

    private void revokeLegacyToken(OperatorRefreshTokenEntity token, String reason) {
        if (token.getRevokedAt() != null) {
            return;
        }
        Instant now = Instant.now();
        token.setRevokedAt(now);
        token.setRevocationReason(reason);
        token.setUpdatedAt(now);
        refreshTokenRepository.save(token);
    }

    private void revokeFamily(
            OperatorSessionFamilyEntity family,
            String reason,
            Instant now
    ) {
        if (family.getRevokedAt() == null) {
            family.setRevokedAt(now);
            family.setRevocationReason(reason);
            sessionFamilyRepository.saveAndFlush(family);
        }
        refreshTokenRepository.revokeActiveFamilyTokens(family.getId(), now, reason);
    }

    private void assertUsable(OperatorRefreshTokenEntity token) {
        if (token.getRevokedAt() != null) {
            throw new OperatorAuthenticationException("Refresh token already revoked");
        }
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            throw new OperatorAuthenticationException("Refresh token expired");
        }
        if (!token.getOperator().isActive()) {
            throw new OperatorAuthenticationException("Operator account is inactive");
        }
    }

    private void assertNoSessionMetadata(String clientType, String deviceLabel) {
        if (clientType != null || deviceLabel != null) {
            throw new OperatorAuthenticationException("Session metadata requires family adoption");
        }
    }

    private SessionInventoryState inventoryState(
            OperatorSessionFamilyEntity family,
            Instant now
    ) {
        if (family.getRevokedAt() != null) {
            return SessionInventoryState.REVOKED;
        }
        if (!family.getAbsoluteExpiresAt().isAfter(now)) {
            return SessionInventoryState.EXPIRED;
        }
        return SessionInventoryState.ACTIVE;
    }

    private void purgeExpiredLegacyTokens() {
        refreshTokenRepository.deleteByExpiresAtBeforeAndSessionFamilyIsNull(Instant.now());
    }

    private String defaultIfBlank(String requested, String fallback) {
        return requested == null || requested.isBlank() ? fallback : requested;
    }

    private void validateMetadata(String clientType, String deviceLabel) {
        if (clientType == null || !CLIENT_TYPE.matcher(clientType).matches()) {
            throw new OperatorAuthenticationException("Invalid session client type");
        }
        if (deviceLabel == null
                || deviceLabel.isBlank()
                || deviceLabel.length() > 120
                || !deviceLabel.equals(deviceLabel.trim())
                || CONTROL_CHARACTER.matcher(deviceLabel).find()) {
            throw new OperatorAuthenticationException("Invalid session device label");
        }
    }

    private String exactAuthenticationMethod(List<String> authenticationMethods) {
        if (authenticationMethods == null
                || authenticationMethods.size() != 1
                || !("pwd".equals(authenticationMethods.get(0))
                        || "webauthn".equals(authenticationMethods.get(0)))) {
            throw new OperatorAuthenticationException("Invalid authentication method");
        }
        return authenticationMethods.get(0);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash refresh token", exception);
        }
    }

    public record IssuedRefreshToken(
            Long persistedId,
            String token,
            Instant expiresAt
    ) {
    }

    public record IssuedSession(
            OperatorEntity operator,
            UUID familyId,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            Instant authenticatedAt,
            List<String> authenticationMethods
    ) {
    }
}
