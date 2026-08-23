package com.atenea.auth.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.RefreshTokenService;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "atenea.auth.privileged-actions.enabled=true",
        "atenea.auth.privileged-actions.enforcement-enabled=false"
})
class PrivilegedActionAuthorizationServiceIntegrationTest {
    @Autowired private PrivilegedActionAuthorizationService service;
    @Autowired private OperatorAuthProperties properties;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private OperatorEntity operator;
    private AuthenticatedOperator actor;
    private AuthenticatedSession session;
    private PrivilegedActionBinding binding;

    @BeforeEach
    void setUp() {
        clearSyntheticState();
        properties.getPrivilegedActions().setEnabled(true);
        properties.getPrivilegedActions().setEnforcementEnabled(false);
        properties.getPrivilegedActions().setAuthorizationTtl(Duration.ofMinutes(5));
        operator = syntheticOperator("primary", CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        actor = actor(operator);
        RefreshTokenService.IssuedSession issued = refreshTokenService.createFamilySession(
                operator, "WEB", "Synthetic privileged browser");
        session = new AuthenticatedSession(actor, issued.familyId(), issued.authenticatedAt(),
                issued.authenticationMethods());
        binding = PrivilegedActionBinding.fromCanonical(
                "PUBLISH_RELEASE", "project=synthetic-a", "commit=abc123;mode=ff");
    }

    @AfterEach
    void tearDown() {
        properties.getPrivilegedActions().setEnabled(false);
        properties.getPrivilegedActions().setEnforcementEnabled(false);
        properties.getPrivilegedActions().setAuthorizationTtl(Duration.ofMinutes(5));
        clearSyntheticState();
    }

    @Test
    void issuesOpaqueFiveMinuteAuthorizationAndConsumesWithDurableAcceptance() {
        PrivilegedActionAuthorizationGrant grant = issue(binding);
        assertTrue(grant.expiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(4))));
        assertFalse(grant.toString().contains(grant.authorization().toString()));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_privileged_action_authorization
                WHERE encode(authorization_digest, 'hex') LIKE ?
                """, "%" + grant.authorization() + "%"));

        UUID receipt = UUID.randomUUID();
        String accepted = service.consumeForAcceptance(
                grant.authorization(), session, binding, () -> {
                    jdbcTemplate.update("""
                            INSERT INTO operator_security_event (
                                id, operator_id, event_type, outcome, occurred_at)
                            VALUES (?, ?, 'SYNTHETIC_ACCEPTED', 'SUCCEEDED', now())
                            """, receipt, operator.getId());
                    return "accepted";
                });
        assertEquals("accepted", accepted);
        assertEquals(1, count("SELECT count(*) FROM operator_security_event WHERE id = ?", receipt));
        assertEquals(1, count("""
                SELECT count(*) FROM operator_privileged_action_authorization
                WHERE consumed_at IS NOT NULL
                """));
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), session, binding, () -> "replayed"));
    }

    @Test
    void deniesCrossActorSessionActionTargetPlanAndStaleVersions() {
        PrivilegedActionAuthorizationGrant grant = issue(binding);
        PrivilegedActionBinding otherAction = PrivilegedActionBinding.fromCanonical(
                "DELETE_RELEASE", "project=synthetic-a", "commit=abc123;mode=ff");
        PrivilegedActionBinding otherTarget = PrivilegedActionBinding.fromCanonical(
                "PUBLISH_RELEASE", "project=synthetic-b", "commit=abc123;mode=ff");
        PrivilegedActionBinding otherPlan = PrivilegedActionBinding.fromCanonical(
                "PUBLISH_RELEASE", "project=synthetic-a", "commit=def456;mode=ff");
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), session, otherAction, () -> "wrong"));
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), session, otherTarget, () -> "wrong"));
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), session, otherPlan, () -> "wrong"));

        OperatorEntity foreign = syntheticOperator("foreign",
                CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        RefreshTokenService.IssuedSession foreignIssued = refreshTokenService.createFamilySession(
                foreign, "WEB", "Foreign synthetic browser");
        AuthenticatedSession foreignSession = new AuthenticatedSession(actor(foreign),
                foreignIssued.familyId(), foreignIssued.authenticatedAt(),
                foreignIssued.authenticationMethods());
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), foreignSession, binding, () -> "wrong"));

        RefreshTokenService.IssuedSession second = refreshTokenService.createFamilySession(
                operator, "WEB", "Second synthetic browser");
        AuthenticatedSession secondSession = new AuthenticatedSession(actor, second.familyId(),
                second.authenticatedAt(), second.authenticationMethods());
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), secondSession, binding, () -> "wrong"));

        operator.setCredentialVersion(operator.getCredentialVersion() + 1);
        operatorRepository.saveAndFlush(operator);
        assertRejected(() -> service.consumeForAcceptance(
                grant.authorization(), session, binding, () -> "stale"));
    }

    @Test
    void deniesWrongRoleRoleVersionExpiryAndDisabledCapability() {
        OperatorEntity routine = syntheticOperator("routine", CodexOperationsRole.ROUTINE_OPERATOR);
        RefreshTokenService.IssuedSession routineSession = refreshTokenService.createFamilySession(
                routine, "WEB", "Routine browser");
        assertRejected(() -> service.issueVerified(new VerifiedStepUp(
                routine.getId(), routineSession.familyId(), binding,
                PrivilegedActionFactor.WEBAUTHN, Instant.now())));

        PrivilegedActionAuthorizationGrant roleStale = issue(binding);
        operator.setRoleVersion(operator.getRoleVersion() + 1);
        operatorRepository.saveAndFlush(operator);
        assertRejected(() -> service.consumeForAcceptance(
                roleStale.authorization(), session, binding, () -> "stale"));

        operator.setRoleVersion(operator.getRoleVersion() - 1);
        operatorRepository.saveAndFlush(operator);
        PrivilegedActionAuthorizationGrant expired = issue(binding);
        jdbcTemplate.update("""
                UPDATE operator_privileged_action_authorization
                SET authenticated_at = now() - interval '11 minutes',
                    created_at = now() - interval '10 minutes',
                    expires_at = now() - interval '5 minutes'
                WHERE id = (SELECT id FROM operator_privileged_action_authorization
                    WHERE consumed_at IS NULL ORDER BY created_at DESC, id DESC LIMIT 1)
                """);
        assertRejected(() -> service.consumeForAcceptance(
                expired.authorization(), session, binding, () -> "expired"));

        properties.getPrivilegedActions().setEnabled(false);
        assertRejected(() -> issue(binding));
        assertFalse(service.enforcementEnabled());
    }

    @Test
    void atomicFailureDoesNotBurnAndConcurrentConsumptionHasOneWinner() throws Exception {
        PrivilegedActionAuthorizationGrant retryable = issue(binding);
        assertThrows(SyntheticAcceptanceException.class, () -> service.consumeForAcceptance(
                retryable.authorization(), session, binding,
                () -> { throw new SyntheticAcceptanceException(); }));
        assertEquals("accepted", service.consumeForAcceptance(
                retryable.authorization(), session, binding, () -> "accepted"));

        PrivilegedActionAuthorizationGrant concurrent = issue(binding);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Callable<Boolean> consume = () -> {
            start.await();
            try {
                service.consumeForAcceptance(concurrent.authorization(), session, binding, () -> {
                    accepted.incrementAndGet();
                    return true;
                });
                return true;
            } catch (OperatorAuthenticationException exception) {
                return false;
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(consume);
            Future<Boolean> second = executor.submit(consume);
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
        assertEquals(1, accepted.get());
    }

    private PrivilegedActionAuthorizationGrant issue(PrivilegedActionBinding value) {
        return service.issueVerified(new VerifiedStepUp(
                operator.getId(), session.sessionFamilyId(), value,
                PrivilegedActionFactor.WEBAUTHN, Instant.now()));
    }

    private OperatorEntity syntheticOperator(String marker, CodexOperationsRole role) {
        Instant now = Instant.now();
        OperatorEntity value = new OperatorEntity();
        value.setEmail("m123-" + marker + "-" + UUID.randomUUID() + "@atenea.test");
        value.setDisplayName("Synthetic M1.2.3 operator");
        value.setPasswordHash(passwordEncoder.encode("synthetic-password"));
        value.setActive(true);
        value.setCodexOperationsRole(role);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return operatorRepository.saveAndFlush(value);
    }

    private AuthenticatedOperator actor(OperatorEntity value) {
        return new AuthenticatedOperator(value.getId(), value.getEmail(), value.getDisplayName());
    }

    private void assertRejected(ThrowingAction action) {
        assertThrows(OperatorAuthenticationException.class, action::run);
    }

    private int count(String sql, Object... values) {
        return jdbcTemplate.queryForObject(sql, Integer.class, values);
    }

    private void clearSyntheticState() {
        jdbcTemplate.update("DELETE FROM operator_privileged_action_authorization");
        jdbcTemplate.update("DELETE FROM operator_security_notification");
        jdbcTemplate.update("DELETE FROM operator_security_event");
        jdbcTemplate.update("DELETE FROM operator_auth_attempt_window");
        jdbcTemplate.update("DELETE FROM operator_recovery_code");
        jdbcTemplate.update("DELETE FROM operator_totp_factor");
        jdbcTemplate.update("DELETE FROM operator_webauthn_challenge");
        jdbcTemplate.update("DELETE FROM operator_webauthn_credential");
        jdbcTemplate.update("DELETE FROM operator_webauthn_user");
        jdbcTemplate.update("DELETE FROM operator_refresh_token");
        jdbcTemplate.update("DELETE FROM operator_session_family");
        jdbcTemplate.update("DELETE FROM operator_account WHERE email LIKE 'm123-%@atenea.test'");
    }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
    private static final class SyntheticAcceptanceException extends RuntimeException { }
}
