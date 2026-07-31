package com.atenea.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atenea.persistence.notification.NotificationCategory;
import com.atenea.persistence.notification.NotificationEventEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationPayloadFactoryTest {

    @Test
    void createsExactVersionedConversationPayloadForEveryCategory() {
        for (NotificationCategory category : NotificationCategory.values()) {
            NotificationPayloadFactory.NotificationPayload payload =
                    NotificationPayloadFactory.create(event(category));

            assertEquals("atenea-notification-data-v1", payload.data().get("schemaVersion"));
            assertEquals("AGENT_RUN_STATE", payload.data().get("eventType"));
            assertEquals(category.name(), payload.data().get("category"));
            assertEquals("agent-run-safe-v1", payload.data().get("templateVersion"));
            assertEquals("WORK_SESSION_CONVERSATION", payload.data().get("deepLinkKind"));
            assertEquals("atenea://work-sessions/12/conversation", payload.data().get("deepLink"));
            assertEquals(Set.of(
                    "schemaVersion", "eventType", "type", "category",
                    "notificationEventId", "templateVersion", "deepLinkKind",
                    "deepLink", "sessionId", "runId"), payload.data().keySet());
            assertFalse(payload.data().toString().contains("synthetic prompt"));
            assertFalse(payload.data().toString().contains("synthetic answer"));
            assertFalse(payload.data().toString().contains("worker-internal"));
            assertFalse(payload.data().toString().contains("secret-value"));
        }
    }

    @Test
    void rejectsUnknownTemplateVersionAndTamperedSafeCopy() {
        NotificationEventEntity unknown = event(NotificationCategory.RUN_COMPLETED);
        unknown.setTemplateVersion("agent-run-safe-v2");
        assertThrows(IllegalArgumentException.class, () -> NotificationPayloadFactory.create(unknown));

        NotificationEventEntity tampered = event(NotificationCategory.RUN_COMPLETED);
        tampered.setSafeBody("synthetic answer");
        assertThrows(IllegalStateException.class, () -> NotificationPayloadFactory.create(tampered));
    }

    private NotificationEventEntity event(NotificationCategory category) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        AgentRunEntity run = new AgentRunEntity();
        run.setId(55L);
        run.setSession(session);
        NotificationTemplateCatalog.SafeTemplate template =
                NotificationTemplateCatalog.resolve(NotificationTemplateCatalog.TEMPLATE_VERSION, category);
        NotificationEventEntity event = new NotificationEventEntity();
        event.setId(UUID.fromString("75972c9f-711d-459e-9bed-43f92fd31df8"));
        event.setCategory(category);
        event.setTemplateVersion(NotificationTemplateCatalog.TEMPLATE_VERSION);
        event.setDeepLinkKind(NotificationOutboxService.DEEP_LINK_KIND);
        event.setSession(session);
        event.setAgentRun(run);
        event.setSafeTitle(template.title());
        event.setSafeBody(template.body());
        return event;
    }
}
