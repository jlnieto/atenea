package com.atenea.service.core;

import com.atenea.api.core.CoreCommandEventResponse;
import com.atenea.api.core.CoreCommandEventsResponse;
import com.atenea.persistence.core.CoreCommandEventEntity;
import com.atenea.persistence.core.CoreCommandEventPhase;
import com.atenea.persistence.core.CoreCommandEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoreCommandEventService {

    private final CoreCommandEventRepository coreCommandEventRepository;
    private final ObjectMapper objectMapper;

    public CoreCommandEventService(
            CoreCommandEventRepository coreCommandEventRepository,
            ObjectMapper objectMapper
    ) {
        this.coreCommandEventRepository = coreCommandEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(Long commandId, CoreCommandEventPhase phase, String message, Object payload) {
        CoreCommandEventEntity event = new CoreCommandEventEntity();
        event.setCommandId(commandId);
        event.setPhase(phase);
        event.setMessage(message);
        event.setPayloadJson(writeJson(payload));
        event.setCreatedAt(Instant.now());
        coreCommandEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public CoreCommandEventsResponse getEvents(Long commandId) {
        return new CoreCommandEventsResponse(
                commandId,
                coreCommandEventRepository.findByCommandIdOrderByCreatedAtDesc(commandId).stream()
                        .sorted(Comparator.comparing(CoreCommandEventEntity::getCreatedAt))
                        .map(this::toResponse)
                        .toList(),
                Instant.now());
    }

    private CoreCommandEventResponse toResponse(CoreCommandEventEntity entity) {
        return new CoreCommandEventResponse(
                entity.getId(),
                entity.getPhase(),
                entity.getMessage(),
                readJson(entity.getPayloadJson()),
                entity.getCreatedAt());
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse persisted Core command event JSON", exception);
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Core command event payload", exception);
        }
    }
}
