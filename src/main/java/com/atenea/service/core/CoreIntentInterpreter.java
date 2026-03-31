package com.atenea.service.core;

import com.atenea.api.core.CreateCoreCommandRequest;

public interface CoreIntentInterpreter {

    CoreInterpretationResult interpret(CreateCoreCommandRequest request);
}
