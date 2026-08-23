package com.atenea.api.v2.control;

public record V2PrimaryActionResponse(
        V2PrimaryActionKind kind,
        String label,
        boolean enabled,
        boolean requiresConfirmation,
        boolean requiresStepUp) {

    public V2PrimaryActionResponse {
        if (kind == null) {
            throw new IllegalArgumentException("Primary action kind is required");
        }
        if (kind == V2PrimaryActionKind.NONE) {
            if (label != null || enabled || requiresConfirmation || requiresStepUp) {
                throw new IllegalArgumentException("NONE cannot expose an executable primary action");
            }
        } else if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("An actionable primary action requires a label");
        }
        if (!enabled && (requiresConfirmation || requiresStepUp)) {
            throw new IllegalArgumentException("A disabled primary action cannot require authorization");
        }
    }

    public static V2PrimaryActionResponse none() {
        return new V2PrimaryActionResponse(V2PrimaryActionKind.NONE, null, false, false, false);
    }

    public static V2PrimaryActionResponse waitForUpdate() {
        return new V2PrimaryActionResponse(
                V2PrimaryActionKind.WAIT,
                "Esperar actualización",
                false,
                false,
                false);
    }

    public static V2PrimaryActionResponse correctRequest() {
        return new V2PrimaryActionResponse(
                V2PrimaryActionKind.CORRECT_REQUEST,
                "Corregir solicitud",
                true,
                false,
                false);
    }

    public static V2PrimaryActionResponse reconcile() {
        return new V2PrimaryActionResponse(
                V2PrimaryActionKind.RECONCILE,
                "Reconciliar estado",
                true,
                false,
                false);
    }

    public static V2PrimaryActionResponse contactPlatformAdministrator() {
        return new V2PrimaryActionResponse(
                V2PrimaryActionKind.CONTACT_PLATFORM_ADMINISTRATOR,
                "Contactar con administración",
                false,
                false,
                false);
    }
}
