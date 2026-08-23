package com.atenea.auth.webauthn;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.recovery.TotpFactorState;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRecoveryCodeEntity;
import com.atenea.persistence.auth.OperatorRecoveryCodeRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorTotpFactorEntity;
import com.atenea.persistence.auth.OperatorTotpFactorRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialEntity;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebAuthnControlledResetService {

    static final int HISTORICAL_CREDENTIAL_COUNT = 4;
    static final int ACTIVE_TOTP_COUNT = 1;
    static final int ACTIVE_RECOVERY_CODE_COUNT = 10;
    private static final String TARGET_PROVIDER = "1Password";
    private static final String REVOCATION_REASON = "CONTROLLED_PASSKEY_RESET";

    private final OperatorRepository operatorRepository;
    private final OperatorWebAuthnCredentialRepository credentialRepository;
    private final OperatorTotpFactorRepository totpFactorRepository;
    private final OperatorRecoveryCodeRepository recoveryCodeRepository;
    private final OperatorAuthProperties properties;

    public WebAuthnControlledResetService(
            OperatorRepository operatorRepository,
            OperatorWebAuthnCredentialRepository credentialRepository,
            OperatorTotpFactorRepository totpFactorRepository,
            OperatorRecoveryCodeRepository recoveryCodeRepository,
            OperatorAuthProperties properties
    ) {
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.totpFactorRepository = totpFactorRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.properties = properties;
    }

    public boolean isEnabled() {
        if (!properties.getWebAuthn().isControlledResetEnabled()) {
            return false;
        }
        requireExactFlagConfiguration();
        return true;
    }

    @Transactional(readOnly = true)
    public WebAuthnControlledResetStatus status(AuthenticatedOperator actor) {
        if (!isEnabled()) {
            return WebAuthnControlledResetStatus.disabled();
        }
        OperatorEntity operator = operatorRepository.findById(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnControlledResetService::rejected);
        return snapshot(
                operator,
                credentialRepository.findAllByOperatorIdOrderByLabelOrdinalAscIdAsc(
                        operator.getId()),
                totpFactorRepository.findAllByOperatorIdAndStateOrderById(
                        operator.getId(), TotpFactorState.ACTIVE),
                recoveryCodeRepository.findAllByOperatorIdOrderById(operator.getId()))
                .status();
    }

    void requireRegistrationStart(OperatorEntity operator) {
        if (!isEnabled()) {
            return;
        }
        ResetSnapshot snapshot = lockedSnapshot(operator);
        if (snapshot.state() != WebAuthnControlledResetState.REGISTER_NEW) {
            throw rejected();
        }
    }

    void requireRegistrationCompletion(
            OperatorEntity operator,
            WebAuthnProviderCategory requestedProvider
    ) {
        if (!isEnabled()) {
            return;
        }
        if (requestedProvider != WebAuthnProviderCategory.ONE_PASSWORD) {
            throw rejected();
        }
        ResetSnapshot snapshot = lockedSnapshot(operator);
        if (snapshot.state() != WebAuthnControlledResetState.REGISTER_NEW) {
            throw rejected();
        }
    }

    void requireProofTarget(OperatorEntity operator, UUID recordId) {
        if (!isEnabled()) {
            return;
        }
        ResetSnapshot snapshot = lockedSnapshot(operator);
        if (snapshot.state() != WebAuthnControlledResetState.PROVE_NEW
                || snapshot.candidate() == null
                || !snapshot.candidate().getId().equals(recordId)) {
            throw rejected();
        }
    }

    @Transactional
    public WebAuthnControlledResetResult commit(
            AuthenticatedOperator actor,
            UUID candidateRecordId
    ) {
        if (!isEnabled() || candidateRecordId == null) {
            throw rejected();
        }
        OperatorEntity operator = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnControlledResetService::rejected);
        ResetSnapshot snapshot = lockedSnapshot(operator);
        if (snapshot.state() != WebAuthnControlledResetState.COMMIT_READY
                || snapshot.candidate() == null
                || !snapshot.candidate().getId().equals(candidateRecordId)) {
            throw rejected();
        }

        Instant revokedAt = Instant.now();
        for (OperatorWebAuthnCredentialEntity credential : snapshot.historical()) {
            if (credential.getRevokedAt() == null) {
                credential.setRevokedAt(revokedAt);
                credential.setRevocationReason(REVOCATION_REASON);
            }
        }
        credentialRepository.saveAllAndFlush(snapshot.historical());
        advanceCredentialVersion(operator, revokedAt);

        long activePasskeys = snapshot.credentials().stream()
                .filter(credential -> credential == snapshot.candidate()
                        && credential.getRevokedAt() == null)
                .count();
        long revokedHistorical = snapshot.historical().stream()
                .filter(credential -> credential.getRevokedAt() != null)
                .count();
        if (activePasskeys != 1L || revokedHistorical != HISTORICAL_CREDENTIAL_COUNT) {
            throw rejected();
        }
        return new WebAuthnControlledResetResult(
                "COMMITTED",
                Math.toIntExact(activePasskeys),
                Math.toIntExact(revokedHistorical),
                snapshot.activeTotpCount(),
                snapshot.activeRecoveryCodeCount(),
                operator.getCredentialVersion());
    }

    private ResetSnapshot lockedSnapshot(OperatorEntity operator) {
        return snapshot(
                operator,
                credentialRepository.findAllByOperatorIdForUpdate(operator.getId()),
                totpFactorRepository.findAllByOperatorIdAndStateForUpdate(
                        operator.getId(), TotpFactorState.ACTIVE),
                recoveryCodeRepository.findAllByOperatorIdForUpdate(operator.getId()));
    }

    private ResetSnapshot snapshot(
            OperatorEntity operator,
            List<OperatorWebAuthnCredentialEntity> credentials,
            List<OperatorTotpFactorEntity> activeTotpFactors,
            List<OperatorRecoveryCodeEntity> recoveryCodes
    ) {
        int activeTotpCount = Math.toIntExact(activeTotpFactors.stream()
                .filter(factor -> factor.getRevokedAt() == null)
                .count());
        int activeRecoveryCodeCount = Math.toIntExact(recoveryCodes.stream()
                .filter(code -> code.getConsumedAt() == null && code.getRevokedAt() == null)
                .count());
        boolean factorsExact = activeTotpCount == ACTIVE_TOTP_COUNT
                && activeRecoveryCodeCount == ACTIVE_RECOVERY_CODE_COUNT;

        List<OperatorWebAuthnCredentialEntity> historical = List.of();
        OperatorWebAuthnCredentialEntity candidate = null;
        WebAuthnControlledResetState state = WebAuthnControlledResetState.BLOCKED;
        if (factorsExact && credentials.size() == HISTORICAL_CREDENTIAL_COUNT) {
            historical = List.copyOf(credentials);
            state = WebAuthnControlledResetState.REGISTER_NEW;
        } else if (factorsExact && credentials.size() == HISTORICAL_CREDENTIAL_COUNT + 1) {
            candidate = credentials.getLast();
            historical = List.copyOf(credentials.subList(0, HISTORICAL_CREDENTIAL_COUNT));
            if (isExactCandidate(candidate, historical)) {
                boolean allHistoricalRevoked = historical.stream()
                        .allMatch(credential -> credential.getRevokedAt() != null);
                if (allHistoricalRevoked) {
                    state = WebAuthnControlledResetState.COMPLETE;
                } else if (candidate.getLastVerifiedAt() != null
                        && candidate.getLastUsedAt() != null) {
                    state = WebAuthnControlledResetState.COMMIT_READY;
                } else {
                    state = WebAuthnControlledResetState.PROVE_NEW;
                }
            }
        }
        return new ResetSnapshot(
                operator,
                List.copyOf(credentials),
                historical,
                candidate,
                activeTotpCount,
                activeRecoveryCodeCount,
                state);
    }

    private boolean isExactCandidate(
            OperatorWebAuthnCredentialEntity candidate,
            List<OperatorWebAuthnCredentialEntity> historical
    ) {
        return candidate.getRevokedAt() == null
                && candidate.getProviderCategory() == WebAuthnProviderCategory.ONE_PASSWORD
                && candidate.getProviderProvenance()
                        == WebAuthnProviderProvenance.OPERATOR_DECLARED
                && historical.stream().allMatch(
                        credential -> credential.getLabelOrdinal() < candidate.getLabelOrdinal());
    }

    private void requireExactFlagConfiguration() {
        OperatorAuthProperties.WebAuthn configured = properties.getWebAuthn();
        if (!configured.isEnabled()
                || !configured.isCredentialLifecycleEnabled()
                || !configured.isCredentialInventoryEnabled()
                || !configured.isRestrictedCeremoniesEnabled()
                || configured.isCredentialSignallingEnabled()) {
            throw new WebAuthnLifecycleUnavailableException(
                    "La configuración del reinicio de passkeys no es coherente.");
        }
    }

    private void advanceCredentialVersion(OperatorEntity operator, Instant updatedAt) {
        try {
            operator.setCredentialVersion(Math.addExact(operator.getCredentialVersion(), 1L));
        } catch (ArithmeticException exception) {
            throw rejected();
        }
        operator.setUpdatedAt(updatedAt);
        operatorRepository.saveAndFlush(operator);
    }

    private static OperatorAuthenticationException rejected() {
        return new OperatorAuthenticationException("WebAuthn ceremony rejected");
    }

    private record ResetSnapshot(
            OperatorEntity operator,
            List<OperatorWebAuthnCredentialEntity> credentials,
            List<OperatorWebAuthnCredentialEntity> historical,
            OperatorWebAuthnCredentialEntity candidate,
            int activeTotpCount,
            int activeRecoveryCodeCount,
            WebAuthnControlledResetState state
    ) {
        private WebAuthnControlledResetStatus status() {
            return new WebAuthnControlledResetStatus(
                    state,
                    TARGET_PROVIDER,
                    HISTORICAL_CREDENTIAL_COUNT,
                    historical.isEmpty() ? null : historical.size(),
                    candidate == null ? null : candidate.getId(),
                    candidate == null ? null : TARGET_PROVIDER + " · "
                            + candidate.getLabelOrdinal(),
                    activeTotpCount,
                    activeRecoveryCodeCount,
                    nextAction(state));
        }

        private static String nextAction(WebAuthnControlledResetState state) {
            return switch (state) {
                case DISABLED -> "El reinicio controlado de passkeys permanece desactivado.";
                case REGISTER_NEW -> "Registra una passkey nueva en 1Password.";
                case PROVE_NEW -> "Verifica la nueva passkey de 1Password.";
                case COMMIT_READY -> "Confirma la revocación de las cuatro passkeys históricas.";
                case COMPLETE -> "Reinicio completado con una passkey activa en 1Password.";
                case BLOCKED -> "El estado de factores no coincide con el contrato sellado.";
            };
        }
    }
}
