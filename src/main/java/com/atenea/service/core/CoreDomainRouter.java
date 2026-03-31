package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CoreDomainRouter {

    private final Map<CoreDomain, CoreDomainHandler> handlersByDomain;

    public CoreDomainRouter(List<CoreDomainHandler> handlers) {
        Map<CoreDomain, CoreDomainHandler> map = new EnumMap<>(CoreDomain.class);
        for (CoreDomainHandler handler : handlers) {
            map.put(handler.domain(), handler);
        }
        this.handlersByDomain = Map.copyOf(map);
    }

    public CoreCommandExecutionResult execute(CoreIntentEnvelope intent, CoreExecutionContext context) {
        CoreDomainHandler handler = handlersByDomain.get(intent.domain());
        if (handler == null) {
            throw new CoreUnsupportedDomainException("No Core domain handler is available for " + intent.domain());
        }
        return handler.execute(intent, context);
    }
}
