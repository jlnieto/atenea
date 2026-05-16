package com.atenea.persistence.voice;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceNoteRepository extends JpaRepository<VoiceNoteEntity, Long> {

    List<VoiceNoteEntity> findByOperatorIdAndStatusOrderByCreatedAtAsc(Long operatorId, VoiceNoteStatus status);

    Optional<VoiceNoteEntity> findByIdAndOperatorIdAndStatus(Long id, Long operatorId, VoiceNoteStatus status);

    Optional<VoiceNoteEntity> findFirstByOperatorIdAndStatusOrderByCreatedAtDesc(Long operatorId, VoiceNoteStatus status);
}
