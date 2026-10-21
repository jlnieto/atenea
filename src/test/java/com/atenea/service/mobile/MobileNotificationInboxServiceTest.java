package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.notification.MobileNotificationInboxEntity;
import com.atenea.persistence.notification.MobileNotificationInboxRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MobileNotificationInboxServiceTest {

    @Autowired MobileNotificationInboxService service;
    @Autowired MobileNotificationInboxRepository repository;
    @Autowired OperatorRepository operators;
    @Autowired EntityManager entityManager;

    @Test
    void markAllReadCoversEarlierPagesWithoutTouchingOtherOperatorsOrNewerAlerts() {
        OperatorEntity owner = operator();
        OperatorEntity other = operator();
        Instant old = Instant.parse("2026-10-16T06:25:11Z");
        for (int index = 0; index < 31; index++) {
            notification(owner, old.plusSeconds(index), null);
        }
        UUID alreadyRead = notification(owner, old.minusSeconds(1), old);
        UUID newer = notification(owner, Instant.now().plusSeconds(60), null);
        UUID otherUnread = notification(other, old, null);
        entityManager.flush();
        entityManager.clear();

        var result = service.markAllRead(owner.getId());
        entityManager.flush();
        entityManager.clear();

        assertEquals(1L, result.unreadCount());
        assertEquals(1L, service.list(owner.getId(), 0).unreadCount());
        assertEquals(1L, service.list(other.getId(), 0).unreadCount());
        assertNotNull(result.readAt());
        assertNotNull(repository.findById(alreadyRead).orElseThrow().getReadAt());
        assertNull(repository.findById(newer).orElseThrow().getReadAt());
        assertNull(repository.findById(otherUnread).orElseThrow().getReadAt());
        assertEquals(31L, repository.findAll().stream()
                .filter(item -> item.getOperator().getId().equals(owner.getId()))
                .filter(item -> !item.getId().equals(alreadyRead) && item.getReadAt() != null)
                .count());

        assertEquals(1L, service.markAllRead(owner.getId()).unreadCount());
    }

    private OperatorEntity operator() {
        Instant now = Instant.now();
        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(UUID.randomUUID() + "@inbox.test");
        operator.setDisplayName("Inbox test");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        return operators.saveAndFlush(operator);
    }

    private UUID notification(OperatorEntity operator, Instant createdAt, Instant readAt) {
        MobileNotificationInboxEntity item = new MobileNotificationInboxEntity();
        item.setId(UUID.randomUUID());
        item.setOperator(operator);
        item.setEventKey(item.getId().toString());
        item.setCategory("TEST");
        item.setTitle("Aviso");
        item.setBody("Detalle");
        item.setDestination("UPDATES");
        item.setCreatedAt(createdAt);
        item.setReadAt(readAt);
        repository.save(item);
        return item.getId();
    }
}
