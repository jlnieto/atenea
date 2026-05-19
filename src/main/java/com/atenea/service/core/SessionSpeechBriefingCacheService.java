package com.atenea.service.core;

import com.atenea.persistence.core.SessionSpeechBriefingCacheEntity;
import com.atenea.persistence.core.SessionSpeechBriefingCacheRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionSpeechBriefingCacheService {

    private final SessionSpeechBriefingCacheRepository repository;

    public SessionSpeechBriefingCacheService(SessionSpeechBriefingCacheRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Optional<SessionSpeechBriefingCacheEntity> find(
            Long sessionId,
            SessionSpeechMode mode,
            String provider,
            String model,
            String promptVersion,
            String sourceHash
    ) {
        Optional<SessionSpeechBriefingCacheEntity> cached =
                repository.findByWorkSessionIdAndModeAndProviderAndModelAndPromptVersionAndSourceHash(
                        sessionId,
                        mode.name(),
                        provider,
                        model,
                        promptVersion,
                        sourceHash);
        cached.ifPresent(entity -> {
            entity.setLastUsedAt(Instant.now());
            repository.save(entity);
        });
        return cached;
    }

    @Transactional
    public SessionSpeechBriefingCacheEntity save(
            Long sessionId,
            SessionSpeechMode mode,
            String provider,
            String model,
            String promptVersion,
            String sourceHash,
            Long sourceTurnId,
            Long latestRunId,
            String text,
            boolean truncated
    ) {
        Instant now = Instant.now();
        SessionSpeechBriefingCacheEntity entity = new SessionSpeechBriefingCacheEntity();
        entity.setWorkSessionId(sessionId);
        entity.setMode(mode.name());
        entity.setProvider(provider);
        entity.setModel(model);
        entity.setPromptVersion(promptVersion);
        entity.setSourceHash(sourceHash);
        entity.setSourceTurnId(sourceTurnId);
        entity.setLatestRunId(latestRunId);
        entity.setText(text);
        entity.setTruncated(truncated);
        entity.setCreatedAt(now);
        entity.setLastUsedAt(now);
        return repository.save(entity);
    }
}
