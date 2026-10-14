package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.ChangeHelpService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ChangeHelpController {
    private final ChangeHelpService help;
    public ChangeHelpController(ChangeHelpService help) { this.help=help; }

    @GetMapping("/api/mobile/sessions/{sessionId}/help")
    public ChangeHelpResponse overview(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return help.overview(sessionId,authenticated(actor));
    }
    @PostMapping("/api/mobile/sessions/{sessionId}/help")
    public ChangeHelpResponse ask(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor, @RequestBody JsonNode request) {
        authenticated(actor);
        var fields=new HashSet<String>(); request.fieldNames().forEachRemaining(fields::add);
        if (!request.isObject() || !fields.contains("question") || !Set.of("question","simple").containsAll(fields)
                || !request.path("question").isTextual() || request.path("question").asText().isBlank()
                || request.path("question").asText().length()>1000
                || request.has("simple") && !request.path("simple").isBoolean()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sólo una pregunta de hasta 1000 caracteres y simple opcional");
        }
        return help.ask(sessionId,actor,request.path("question").asText().trim(),request.path("simple").asBoolean(false));
    }
    private static AuthenticatedOperator authenticated(AuthenticatedOperator actor) {
        if (actor==null || actor.operatorId()==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return actor;
    }
}
