package com.atenea.service.v2control;

import java.util.UUID;

public record V2AuditOutboxResult(UUID auditEventId, UUID outboxEventId) {
}
