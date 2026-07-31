package com.atenea.persistence.notification;

public enum NotificationCategory {
    RUN_COMPLETED("Tarea completada", "Abre Atenea para revisar el resultado"),
    RUN_FAILED("La tarea necesita atención", "Abre Atenea para revisar el fallo y el siguiente paso"),
    ACTION_REQUIRED("Se necesita una acción", "Abre Atenea para continuar esta sesión");

    private final String safeTitle;
    private final String safeBody;

    NotificationCategory(String safeTitle, String safeBody) {
        this.safeTitle = safeTitle;
        this.safeBody = safeBody;
    }

    public String safeTitle() { return safeTitle; }
    public String safeBody() { return safeBody; }
}
