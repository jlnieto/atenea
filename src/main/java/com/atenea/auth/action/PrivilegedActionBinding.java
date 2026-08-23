package com.atenea.auth.action;

import com.atenea.auth.OperatorAuthenticationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

public record PrivilegedActionBinding(
        String actionKind,
        byte[] targetFingerprint,
        byte[] planFingerprint
) {
    private static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    private static final int MAX_CANONICAL_BYTES = 4096;

    public PrivilegedActionBinding {
        if (actionKind == null || !ACTION.matcher(actionKind).matches()
                || targetFingerprint == null || targetFingerprint.length != 32
                || planFingerprint == null || planFingerprint.length != 32) {
            throw rejected();
        }
        targetFingerprint = targetFingerprint.clone();
        planFingerprint = planFingerprint.clone();
    }

    public static PrivilegedActionBinding fromCanonical(
            String actionKind,
            String canonicalTarget,
            String canonicalPlan
    ) {
        return new PrivilegedActionBinding(
                actionKind,
                digest("atenea-v2-target\u0000", canonicalTarget),
                digest("atenea-v2-plan\u0000", canonicalPlan));
    }

    @Override public byte[] targetFingerprint() { return targetFingerprint.clone(); }
    @Override public byte[] planFingerprint() { return planFingerprint.clone(); }

    private static byte[] digest(String domain, String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) throw rejected();
        byte[] canonical = value.getBytes(StandardCharsets.UTF_8);
        if (canonical.length > MAX_CANONICAL_BYTES) throw rejected();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            return digest.digest(canonical);
        } catch (NoSuchAlgorithmException exception) {
            throw rejected();
        }
    }

    private static OperatorAuthenticationException rejected() {
        return new OperatorAuthenticationException("Privileged action authorization rejected");
    }
}
