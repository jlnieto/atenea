package com.atenea.persistence.voice;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceCommandTelemetryRepository extends JpaRepository<VoiceCommandTelemetryEntity, Long> {

    List<VoiceCommandTelemetryEntity> findByOperatorIdOrderByCreatedAtDesc(Long operatorId);
}
