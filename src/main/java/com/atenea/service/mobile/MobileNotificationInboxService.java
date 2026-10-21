package com.atenea.service.mobile;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.notification.MobileNotificationInboxEntity;
import com.atenea.persistence.notification.MobileNotificationInboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class MobileNotificationInboxService {
    private final MobileNotificationInboxRepository repository;

    public MobileNotificationInboxService(MobileNotificationInboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(OperatorEntity operator, String eventKey, String category, String title,
                       String body, String destination, Long targetId) {
        if (repository.existsByOperatorIdAndEventKey(operator.getId(), eventKey)) return;
        MobileNotificationInboxEntity item = new MobileNotificationInboxEntity();
        item.setId(UUID.randomUUID());
        item.setOperator(operator);
        item.setEventKey(eventKey);
        item.setCategory(category);
        item.setTitle(title.substring(0, Math.min(title.length(), 190)));
        item.setBody(body.substring(0, Math.min(body.length(), 500)));
        item.setDestination(destination);
        item.setTargetId(targetId);
        item.setCreatedAt(Instant.now());
        repository.save(item);
    }

    @Transactional(readOnly = true)
    public Inbox list(Long operatorId, int page) {
        if (page < 0 || page > 99) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var slice = repository.findByOperatorIdOrderByCreatedAtDesc(operatorId, PageRequest.of(page, 30));
        List<Item> items = slice.getContent().stream().map(Item::from).toList();
        return new Inbox("atenea-mobile-notification-inbox/v1", items, repository.countByOperatorIdAndReadAtIsNull(operatorId),
                slice.hasNext() && page < 99 ? page + 1 : null);
    }

    @Transactional
    public Item markRead(Long operatorId, UUID id) {
        var item = repository.findByIdAndOperatorId(id, operatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (item.getReadAt() == null) item.setReadAt(Instant.now());
        return Item.from(item);
    }

    @Transactional
    public ReadAllResult markAllRead(Long operatorId) {
        Instant readAt = Instant.now();
        repository.markAllRead(operatorId, readAt);
        return new ReadAllResult(readAt, repository.countByOperatorIdAndReadAtIsNull(operatorId));
    }

    @Transactional
    public void markOpened(Long operatorId, UUID eventId) {
        repository.findByOperatorIdAndEventKey(operatorId, eventId.toString()).ifPresent(item -> {
            if (item.getReadAt() == null) item.setReadAt(Instant.now());
        });
    }

    public record Inbox(String protocolVersion, List<Item> items, long unreadCount, Integer nextPage) {}
    public record ReadAllResult(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant readAt, long unreadCount) {}
    public record Item(UUID id, String category, String title, String body, String destination,
                       Long targetId, Instant createdAt, Instant readAt) {
        static Item from(MobileNotificationInboxEntity entity) {
            return new Item(entity.getId(), entity.getCategory(), entity.getTitle(), entity.getBody(),
                    entity.getDestination(), entity.getTargetId(), entity.getCreatedAt(), entity.getReadAt());
        }
    }
}
