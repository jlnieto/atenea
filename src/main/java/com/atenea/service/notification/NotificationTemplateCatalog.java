package com.atenea.service.notification;

import com.atenea.persistence.notification.NotificationCategory;

final class NotificationTemplateCatalog {

    static final String TEMPLATE_VERSION = "agent-run-safe-v1";

    private NotificationTemplateCatalog() {
    }

    static SafeTemplate resolve(String version, NotificationCategory category) {
        if (!TEMPLATE_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported notification template version");
        }
        return switch (category) {
            case RUN_COMPLETED -> new SafeTemplate(
                    "Tarea completada",
                    "Abre Atenea para revisar el resultado");
            case RUN_FAILED -> new SafeTemplate(
                    "La tarea necesita atención",
                    "Abre Atenea para revisar el fallo y el siguiente paso");
            case ACTION_REQUIRED -> new SafeTemplate(
                    "Se necesita una acción",
                    "Abre Atenea para continuar esta sesión");
        };
    }

    record SafeTemplate(String title, String body) {
    }
}
