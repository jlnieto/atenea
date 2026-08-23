package com.atenea.api.developmentchange;

public record DevelopmentChangeActionResponse(
        DevelopmentChangeActionKind kind,
        String label,
        boolean enabled) {

    public DevelopmentChangeActionResponse {
        if (kind == null) {
            throw new IllegalArgumentException("Development change action kind is required");
        }
        if (kind == DevelopmentChangeActionKind.NONE) {
            if (label != null || enabled) {
                throw new IllegalArgumentException("NONE cannot expose an action");
            }
        } else if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("An action label is required");
        }
    }

    public static DevelopmentChangeActionResponse waitForEnablement() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.WAIT_FOR_ENABLEMENT,
                "Esperar habilitación",
                false);
    }

    public static DevelopmentChangeActionResponse bindSession() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.BIND_SESSION,
                "Vincular sesión",
                true);
    }

    public static DevelopmentChangeActionResponse provisionWorkspace() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.PROVISION_WORKSPACE,
                "Preparar workspace",
                true);
    }

    public static DevelopmentChangeActionResponse waitForWorkspace() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.WAIT_FOR_WORKSPACE,
                "Esperar workspace",
                false);
    }

    public static DevelopmentChangeActionResponse inspectWorkspace() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.INSPECT_WORKSPACE,
                "Comprobar workspace",
                true);
    }

    public static DevelopmentChangeActionResponse reconcileWorkspace() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.RECONCILE_WORKSPACE,
                "Reconciliar respuesta",
                true);
    }

    public static DevelopmentChangeActionResponse reviewStaleSource() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.REVIEW_STALE_SOURCE,
                "Revisar fuente desactualizada",
                false);
    }

    public static DevelopmentChangeActionResponse resolveOwnership() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.RESOLVE_OWNERSHIP,
                "Resolver ownership",
                false);
    }

    public static DevelopmentChangeActionResponse continueSession() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.CONTINUE_SESSION,
                "Continuar sesión",
                true);
    }

    public static DevelopmentChangeActionResponse none() {
        return new DevelopmentChangeActionResponse(
                DevelopmentChangeActionKind.NONE,
                null,
                false);
    }
}
