package com.atenea.service.notification;

import com.atenea.persistence.notification.NotificationEventEntity;
import java.util.Map;

final class NotificationPayloadFactory {

    static final String PAYLOAD_SCHEMA_VERSION = "atenea-notification-data-v1";
    static final String EVENT_TYPE = "AGENT_RUN_STATE";
    static final String DEEP_LINK_SCHEME = "atenea";

    private NotificationPayloadFactory() {
    }

    static NotificationPayload create(NotificationEventEntity event) {
        var template = NotificationTemplateCatalog.resolve(
                event.getTemplateVersion(), event.getCategory());
        if (!template.title().equals(event.getSafeTitle())
                || !template.body().equals(event.getSafeBody())
                || !NotificationOutboxService.DEEP_LINK_KIND.equals(event.getDeepLinkKind())) {
            throw new IllegalStateException("Persisted notification template identity is inconsistent");
        }
        Long sessionId = event.getSession().getId();
        Long runId = event.getAgentRun().getId();
        if (sessionId == null || sessionId <= 0 || runId == null || runId <= 0
                || !sessionId.equals(event.getAgentRun().getSession().getId())) {
            throw new IllegalStateException("Persisted notification ownership is inconsistent");
        }
        String deepLink = DEEP_LINK_SCHEME + "://work-sessions/" + sessionId + "/conversation";
        return new NotificationPayload(
                template.title(),
                template.body(),
                Map.of(
                        "schemaVersion", PAYLOAD_SCHEMA_VERSION,
                        "eventType", EVENT_TYPE,
                        "type", event.getCategory().name(),
                        "category", event.getCategory().name(),
                        "notificationEventId", event.getId().toString(),
                        "templateVersion", event.getTemplateVersion(),
                        "deepLinkKind", event.getDeepLinkKind(),
                        "deepLink", deepLink,
                        "sessionId", sessionId,
                        "runId", runId));
    }

    record NotificationPayload(String title, String body, Map<String, Object> data) {
    }
}
