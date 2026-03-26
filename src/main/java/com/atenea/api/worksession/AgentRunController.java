package com.atenea.api.worksession;

import com.atenea.service.worksession.AgentRunService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentRunController {

    private final AgentRunService agentRunService;

    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @GetMapping("/api/sessions/{sessionId}/runs")
    public List<AgentRunResponse> getRuns(@PathVariable Long sessionId) {
        return agentRunService.getRuns(sessionId);
    }
}
