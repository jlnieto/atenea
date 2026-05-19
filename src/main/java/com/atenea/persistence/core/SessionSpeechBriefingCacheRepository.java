package com.atenea.persistence.core;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionSpeechBriefingCacheRepository extends JpaRepository<SessionSpeechBriefingCacheEntity, Long> {

    Optional<SessionSpeechBriefingCacheEntity> findByWorkSessionIdAndModeAndProviderAndModelAndPromptVersionAndSourceHash(
            Long workSessionId,
            String mode,
            String provider,
            String model,
            String promptVersion,
            String sourceHash
    );
}
