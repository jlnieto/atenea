package com.atenea.service.core;

import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreInterpreterSource;
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
}
