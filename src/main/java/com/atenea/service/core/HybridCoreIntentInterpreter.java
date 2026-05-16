package com.atenea.service.core;

import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreInterpreterSource;
import java.text.Normalizer;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class HybridCoreIntentInterpreter implements CoreIntentInterpreter {

    private final DefaultCoreIntentInterpreter defaultCoreIntentInterpreter;
    private final LlmCoreIntentInterpreter llmCoreIntentInterpreter;
    private final CoreLlmProperties coreLlmProperties;

    public HybridCoreIntentInterpreter(
            DefaultCoreIntentInterpreter defaultCoreIntentInterpreter,
            LlmCoreIntentInterpreter llmCoreIntentInterpreter,
            CoreLlmProperties coreLlmProperties
    ) {
        this.defaultCoreIntentInterpreter = defaultCoreIntentInterpreter;
        this.llmCoreIntentInterpreter = llmCoreIntentInterpreter;
        this.coreLlmProperties = coreLlmProperties;
    }

    @Override
    public CoreInterpretationResult interpret(CreateCoreCommandRequest request) {
        if (hasExplicitExecutionContext(request)) {
            return defaultCoreIntentInterpreter.interpret(request);
        }

        if (looksLikeOperationsCommand(request.input())) {
            return defaultCoreIntentInterpreter.interpret(request);
        }

        if (!coreLlmProperties.isEnabled()) {
            return defaultCoreIntentInterpreter.interpret(request);
        }

        try {
            return llmCoreIntentInterpreter.interpret(request);
        } catch (RuntimeException exception) {
            CoreInterpretationResult fallback = defaultCoreIntentInterpreter.interpret(request);
            return new CoreInterpretationResult(
                    fallback.proposal(),
                    CoreInterpreterSource.DETERMINISTIC_FALLBACK,
                    "llm_failed:" + exception.getClass().getSimpleName());
        }
    }

    private boolean hasExplicitExecutionContext(CreateCoreCommandRequest request) {
        return request.context() != null
                && request.context().workSessionId() != null;
    }

    private boolean looksLikeOperationsCommand(String input) {
        String normalized = normalize(input);
        return normalized.contains("apache")
                || normalized.contains("servidor")
                || normalized.contains("dedicado")
                || normalized.contains("vps")
                || normalized.contains("incidencia")
                || normalized.contains("incidencias")
                || normalized.contains("host")
                || normalized.contains("hosts");
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
    }
}
