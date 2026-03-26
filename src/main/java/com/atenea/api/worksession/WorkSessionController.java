package com.atenea.api.worksession;

import com.atenea.service.worksession.WorkSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkSessionController {

    private final WorkSessionService workSessionService;

    public WorkSessionController(WorkSessionService workSessionService) {
        this.workSessionService = workSessionService;
    }

    @PostMapping("/api/projects/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkSessionResponse openSession(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateWorkSessionRequest request
    ) {
        return workSessionService.openSession(projectId, request);
    }

    @GetMapping("/api/sessions/{sessionId}")
    public WorkSessionResponse getSession(@PathVariable Long sessionId) {
        return workSessionService.getSession(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/close")
    public WorkSessionResponse closeSession(@PathVariable Long sessionId) {
        return workSessionService.closeSession(sessionId);
    }
}
