package com.atenea.api.worksession;

import com.atenea.service.worksession.SessionTurnService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionTurnController {

    private final SessionTurnService sessionTurnService;

    public SessionTurnController(SessionTurnService sessionTurnService) {
        this.sessionTurnService = sessionTurnService;
    }

    @GetMapping("/api/sessions/{sessionId}/turns")
    public List<SessionTurnResponse> getTurns(@PathVariable Long sessionId) {
        return sessionTurnService.getTurns(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionTurnResponse createTurn(
            @PathVariable Long sessionId,
            @Valid @RequestBody CreateSessionTurnRequest request
    ) {
        return sessionTurnService.createTurn(sessionId, request);
    }
}
