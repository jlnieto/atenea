package com.atenea.auth.webauthn;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialEntity;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import com.atenea.persistence.auth.OperatorWebAuthnUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebAuthnCredentialLifecycleService {

    private static final Set<WebAuthnProviderCategory> IMPLEMENTED_DOMAINS =
            EnumSet.of(
                    WebAuthnProviderCategory.GOOGLE_PASSWORD_MANAGER,
                    WebAuthnProviderCategory.ONE_PASSWORD);

    private final OperatorRepository operatorRepository;
    private final OperatorWebAuthnCredentialRepository credentialRepository;
    private final OperatorWebAuthnUserRepository userRepository;
    private final OperatorAuthProperties properties;

    public WebAuthnCredentialLifecycleService(
            OperatorRepository operatorRepository,
            OperatorWebAuthnCredentialRepository credentialRepository,
            OperatorWebAuthnUserRepository userRepository,
            OperatorAuthProperties properties
    ) {
        this.operatorRepository = operatorRepository;
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    public boolean isLifecycleEnabled() {
        assertFlagDependencies();
        return properties.getWebAuthn().isCredentialLifecycleEnabled();
    }

    public boolean isRestrictedCeremonyEnabled() {
        assertFlagDependencies();
        return properties.getWebAuthn().isRestrictedCeremoniesEnabled();
    }

    public boolean isSignallingEnabled() {
        assertFlagDependencies();
        return properties.getWebAuthn().isCredentialSignallingEnabled();
    }

    public boolean isControlledResetEnabled() {
        assertFlagDependencies();
        return properties.getWebAuthn().isControlledResetEnabled();
    }

    public List<WebAuthnOptionsResponse.CredentialDescriptor> registrationExclusions(
            Long operatorId
    ) {
        if (!isLifecycleEnabled()) {
            return List.of();
        }
        return credentialRepository.findAllByOperatorIdOrderByLabelOrdinalAscIdAsc(operatorId)
                .stream()
                .map(this::descriptor)
                .toList();
    }

    public List<WebAuthnOptionsResponse.CredentialDescriptor> activeCredentials(
            Long operatorId
    ) {
        return credentialRepository
                .findAllByOperatorIdAndRevokedAtIsNullOrderByLabelOrdinalAscIdAsc(operatorId)
                .stream()
                .map(this::descriptor)
                .toList();
    }

    public WebAuthnOptionsResponse.CredentialDescriptor activeCredential(
            Long operatorId,
            UUID recordId
    ) {
        if (!isRestrictedCeremonyEnabled() || recordId == null) {
            throw rejected();
        }
        return credentialRepository.findByIdAndOperatorIdForUpdate(recordId, operatorId)
                .filter(credential -> credential.getRevokedAt() == null)
                .map(this::descriptor)
                .orElseThrow(WebAuthnCredentialLifecycleService::rejected);
    }

    public void initializeCredential(
            OperatorWebAuthnCredentialEntity credential,
            WebAuthnProviderCategory requestedCategory
    ) {
        assertFlagDependencies();
        List<OperatorWebAuthnCredentialEntity> existing = credentialRepository
                .findAllByOperatorIdOrderByLabelOrdinalAscIdAsc(
                        credential.getOperator().getId());
        long nextOrdinal = existing.stream()
                .mapToLong(OperatorWebAuthnCredentialEntity::getLabelOrdinal)
                .max()
                .orElse(0L) + 1L;
        credential.setLabelOrdinal(nextOrdinal);
        if (properties.getWebAuthn().isCredentialLifecycleEnabled()
                && IMPLEMENTED_DOMAINS.contains(requestedCategory)) {
            credential.setProviderCategory(requestedCategory);
            credential.setProviderProvenance(WebAuthnProviderProvenance.OPERATOR_DECLARED);
        } else {
            credential.setProviderCategory(WebAuthnProviderCategory.UNKNOWN);
            credential.setProviderProvenance(WebAuthnProviderProvenance.UNKNOWN);
        }
    }

    @Transactional(readOnly = true)
    public WebAuthnCredentialInventoryResponse inventory(AuthenticatedOperator actor) {
        assertFlagDependencies();
        OperatorAuthProperties.WebAuthn configured = properties.getWebAuthn();
        if (!configured.isCredentialInventoryEnabled()) {
            return WebAuthnCredentialInventoryResponse.disabled();
        }
        List<WebAuthnCredentialInventoryItem> items = credentialRepository
                .findAllByOperatorIdOrderByLabelOrdinalAscIdAsc(actor.operatorId())
                .stream()
                .map(this::inventoryItem)
                .toList();
        List<WebAuthnProviderCategory> verified = items.stream()
                .filter(item -> item.state() == WebAuthnCredentialState.ACTIVE)
                .filter(item -> item.lastVerifiedAt() != null)
                .map(WebAuthnCredentialInventoryItem::providerCategory)
                .filter(IMPLEMENTED_DOMAINS::contains)
                .distinct()
                .sorted()
                .toList();
        boolean ready = verified.containsAll(IMPLEMENTED_DOMAINS);
        boolean readOnly = configured.isRestrictedCeremoniesEnabled();
        String nextAction = readOnly
                ? "Selecciona y verifica una sola passkey activa; no se permiten altas ni revocaciones."
                : ready
                        ? "Los dos dominios independientes están verificados."
                        : "Verifica una passkey activa de Google Password Manager y otra de 1Password.";
        return new WebAuthnCredentialInventoryResponse(
                ready ? "READY" : "ACTION_REQUIRED",
                items,
                List.of(
                        WebAuthnProviderCategory.GOOGLE_PASSWORD_MANAGER,
                        WebAuthnProviderCategory.ONE_PASSWORD),
                verified,
                ready,
                configured.isCredentialSignallingEnabled(),
                readOnly,
                nextAction);
    }

    @Transactional
    public void revoke(AuthenticatedOperator actor, UUID recordId) {
        requireInventoryEnabled();
        requireCredentialManagementAllowed();
        OperatorEntity operator = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnCredentialLifecycleService::rejected);
        OperatorWebAuthnCredentialEntity credential = credentialRepository
                .findByIdAndOperatorIdForUpdate(recordId, actor.operatorId())
                .orElseThrow(WebAuthnCredentialLifecycleService::rejected);
        if (credential.getRevokedAt() != null) {
            return;
        }
        credential.setRevokedAt(Instant.now());
        credential.setRevocationReason("OPERATOR_REVOKED");
        credentialRepository.saveAndFlush(credential);
        advanceCredentialVersion(operator);
    }

    @Transactional
    public WebAuthnCredentialSignalSnapshot signalSnapshot(AuthenticatedOperator actor) {
        requireSignallingEnabled();
        OperatorEntity operator = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnCredentialLifecycleService::rejected);
        List<OperatorWebAuthnCredentialEntity> active = credentialRepository
                .findAllByOperatorIdAndRevokedAtIsNullOrderByLabelOrdinalAscIdAsc(
                        actor.operatorId());
        long independentlyCounted = credentialRepository
                .countByOperatorIdAndRevokedAtIsNull(actor.operatorId());
        if (active.isEmpty() || independentlyCounted != active.size()) {
            throw new WebAuthnSnapshotIncompleteException();
        }
        byte[] userHandle = userRepository.findById(actor.operatorId())
                .orElseThrow(WebAuthnSnapshotIncompleteException::new)
                .getUserHandle();
        if (userHandle == null || userHandle.length == 0) {
            throw new WebAuthnSnapshotIncompleteException();
        }
        List<String> credentialIds = active.stream()
                .map(OperatorWebAuthnCredentialEntity::getCredentialId)
                .map(this::encode)
                .toList();
        return new WebAuthnCredentialSignalSnapshot(
                properties.getWebAuthn().getRelyingPartyId(),
                encode(userHandle),
                credentialIds,
                active.size(),
                operator.getCredentialVersion());
    }

    public void markVerified(
            OperatorWebAuthnCredentialEntity credential,
            WebAuthnProviderCategory assertedCategory,
            Instant verifiedAt
    ) {
        if (!isLifecycleEnabled()) {
            return;
        }
        if (assertedCategory != null
                && assertedCategory != WebAuthnProviderCategory.UNKNOWN) {
            if (!IMPLEMENTED_DOMAINS.contains(assertedCategory)) {
                throw rejected();
            }
            credential.setProviderCategory(assertedCategory);
            credential.setProviderProvenance(WebAuthnProviderProvenance.OPERATOR_DECLARED);
        }
        credential.setLastVerifiedAt(verifiedAt);
    }

    public void requireCredentialManagementAllowed() {
        assertFlagDependencies();
        if (properties.getWebAuthn().isRestrictedCeremoniesEnabled()) {
            throw new WebAuthnLifecycleUnavailableException(
                    "El discovery de passkeys está en modo de solo lectura.");
        }
    }

    public void requireRegistrationAllowed() {
        assertFlagDependencies();
        OperatorAuthProperties.WebAuthn configured = properties.getWebAuthn();
        if (configured.isRestrictedCeremoniesEnabled()
                && !configured.isControlledResetEnabled()) {
            throw new WebAuthnLifecycleUnavailableException(
                    "El discovery de passkeys está en modo de solo lectura.");
        }
    }

    public String label(OperatorWebAuthnCredentialEntity credential) {
        WebAuthnProviderCategory category = credential.getProviderCategory() == null
                ? WebAuthnProviderCategory.UNKNOWN
                : credential.getProviderCategory();
        return category.sanitizedLabel() + " · " + credential.getLabelOrdinal();
    }

    private WebAuthnCredentialInventoryItem inventoryItem(
            OperatorWebAuthnCredentialEntity credential
    ) {
        WebAuthnProviderCategory category = credential.getProviderCategory() == null
                ? WebAuthnProviderCategory.UNKNOWN
                : credential.getProviderCategory();
        WebAuthnProviderProvenance provenance = credential.getProviderProvenance() == null
                ? WebAuthnProviderProvenance.UNKNOWN
                : credential.getProviderProvenance();
        return new WebAuthnCredentialInventoryItem(
                credential.getId(),
                label(credential),
                category,
                provenance,
                credential.isBackupEligible(),
                credential.isBackupState(),
                transportNames(credential.getTransports()),
                rounded(credential.getCreatedAt()),
                rounded(credential.getLastUsedAt()),
                rounded(credential.getLastVerifiedAt()),
                credential.getRevokedAt() == null
                        ? WebAuthnCredentialState.ACTIVE
                        : WebAuthnCredentialState.REVOKED);
    }

    private List<String> transportNames(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : List.of(value.split(","));
    }

    private Instant rounded(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MINUTES);
    }

    private WebAuthnOptionsResponse.CredentialDescriptor descriptor(
            OperatorWebAuthnCredentialEntity credential
    ) {
        return new WebAuthnOptionsResponse.CredentialDescriptor(
                "public-key",
                encode(credential.getCredentialId()),
                transportNames(credential.getTransports()));
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void requireInventoryEnabled() {
        assertFlagDependencies();
        if (!properties.getWebAuthn().isCredentialInventoryEnabled()) {
            throw new WebAuthnLifecycleUnavailableException(
                    "El inventario correctivo de passkeys permanece desactivado.");
        }
    }

    private void requireSignallingEnabled() {
        assertFlagDependencies();
        if (!properties.getWebAuthn().isCredentialSignallingEnabled()) {
            throw new WebAuthnLifecycleUnavailableException(
                    "La señalización de passkeys permanece desactivada.");
        }
    }

    private void assertFlagDependencies() {
        OperatorAuthProperties.WebAuthn configured = properties.getWebAuthn();
        boolean invalid = (configured.isCredentialInventoryEnabled()
                && !configured.isCredentialLifecycleEnabled())
                || (configured.isCredentialSignallingEnabled()
                        && (!configured.isCredentialLifecycleEnabled()
                                || !configured.isCredentialInventoryEnabled()))
                || (configured.isRestrictedCeremoniesEnabled()
                        && (!configured.isCredentialLifecycleEnabled()
                                || configured.isCredentialSignallingEnabled()))
                || (configured.isControlledResetEnabled()
                        && (!configured.isCredentialLifecycleEnabled()
                                || !configured.isCredentialInventoryEnabled()
                                || !configured.isRestrictedCeremoniesEnabled()
                                || configured.isCredentialSignallingEnabled()));
        if (invalid) {
            throw new WebAuthnLifecycleUnavailableException(
                    "La configuración correctiva de passkeys no es coherente.");
        }
    }

    private void advanceCredentialVersion(OperatorEntity operator) {
        try {
            operator.setCredentialVersion(Math.addExact(operator.getCredentialVersion(), 1L));
        } catch (ArithmeticException exception) {
            throw rejected();
        }
        operator.setUpdatedAt(Instant.now());
        operatorRepository.saveAndFlush(operator);
    }

    private static OperatorAuthenticationException rejected() {
        return new OperatorAuthenticationException("WebAuthn ceremony rejected");
    }
}
