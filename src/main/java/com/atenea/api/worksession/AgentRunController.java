package com.atenea.api.worksession;

import com.atenea.service.worksession.AgentRunService;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
public class AgentRunController {

    private final AgentRunService agentRunService;
    private RemoteAgentRunCoordinator remoteAgentRunCoordinator;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Autowired(required = false)
    void setRemoteAgentRunCoordinator(RemoteAgentRunCoordinator remoteAgentRunCoordinator) {
        this.remoteAgentRunCoordinator = remoteAgentRunCoordinator;
    }

    @GetMapping("/api/sessions/{sessionId}/runs")
    public List<AgentRunResponse> getRuns(@PathVariable Long sessionId) {
        return agentRunService.getRuns(sessionId);
    }

    @PostMapping("/api/runs/{runId}/cancel")
    public void cancel(@PathVariable Long runId) {
        if (remoteAgentRunCoordinator == null) {
            throw new IllegalStateException("Remote AgentRun coordinator is unavailable");
        }
        remoteAgentRunCoordinator.requestCancellation(runId);
    }
}
