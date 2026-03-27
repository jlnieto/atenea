package com.atenea.service.worksession;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AgentRunStartupReconciliationRunner implements ApplicationRunner {

    private final AgentRunReconciliationService agentRunReconciliationService;

    public AgentRunStartupReconciliationRunner(AgentRunReconciliationService agentRunReconciliationService) {
        this.agentRunReconciliationService = agentRunReconciliationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        agentRunReconciliationService.reconcileRunningRunsAfterStartup();
    }
}
