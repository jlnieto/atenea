package com.atenea.api.worksession;

import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionTurnController {

    private final SessionTurnService sessionTurnService;
    private final WorkSessionService workSessionService;

    public SessionTurnController(
            SessionTurnService sessionTurnService,
            WorkSessionService workSessionService
    ) {
        this.sessionTurnService = sessionTurnService;
        this.workSessionService = workSessionService;
    }

    @GetMapping("/api/sessions/{sessionId}/turns")
    public List<SessionTurnResponse> getTurns(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Long beforeTurnId,
            @RequestParam(required = false) Integer limit
    ) {
        return sessionTurnService.getTurns(sessionId, beforeTurnId, limit);
    }

    @PostMapping("/api/sessions/{sessionId}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionTurnResponse createTurn(
            @PathVariable Long sessionId,
            @Valid @RequestBody CreateSessionTurnRequest request
    ) {
        return sessionTurnService.createTurn(sessionId, request);
    }

    @PostMapping("/api/sessions/{sessionId}/turns/conversation-view")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionTurnConversationViewResponse createTurnConversationView(
            @PathVariable Long sessionId,
            @Valid @RequestBody CreateSessionTurnRequest request
    ) {
        sessionTurnService.createTurn(sessionId, request);
        return new CreateSessionTurnConversationViewResponse(workSessionService.getSessionConversationView(sessionId));
    }
}
