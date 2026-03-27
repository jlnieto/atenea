package com.atenea.service.worksession;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class AgentRunStartupReconciliationRunnerTest {

    @Mock
    private AgentRunReconciliationService agentRunReconciliationService;

    @Test
    void runTriggersStartupReconciliation() throws Exception {
        AgentRunStartupReconciliationRunner runner = new AgentRunStartupReconciliationRunner(agentRunReconciliationService);

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(agentRunReconciliationService).reconcileRunningRunsAfterStartup();
    }
}
