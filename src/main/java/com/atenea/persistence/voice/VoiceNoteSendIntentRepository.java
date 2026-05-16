package com.atenea.persistence.voice;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceNoteSendIntentRepository extends JpaRepository<VoiceNoteSendIntentEntity, Long> {

    Optional<VoiceNoteSendIntentEntity> findFirstByOperatorIdAndStatusOrderByCreatedAtDesc(
            Long operatorId,
            VoiceNoteSendIntentStatus status);

    Optional<VoiceNoteSendIntentEntity> findFirstByOperatorIdAndStatusOrderBySentAtDesc(
            Long operatorId,
            VoiceNoteSendIntentStatus status);

    Optional<VoiceNoteSendIntentEntity> findByIdAndOperatorId(Long id, Long operatorId);
}
