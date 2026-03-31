package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;

public interface CoreDomainHandler {

    CoreDomain domain();

    CoreCommandExecutionResult execute(CoreIntentEnvelope intent, CoreExecutionContext context);
}
