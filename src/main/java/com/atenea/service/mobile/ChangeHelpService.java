package com.atenea.service.mobile;

import com.atenea.api.mobile.ChangeHelpResponse;
import com.atenea.auth.AuthenticatedOperator;
import org.springframework.stereotype.Service;

@Service
public class ChangeHelpService {
    private final ChangeHelpContextService contexts;
    private final ChangeHelpExplainer explainer;
    public ChangeHelpService(ChangeHelpContextService contexts, ChangeHelpExplainer explainer) {
        this.contexts=contexts; this.explainer=explainer;
    }
    public ChangeHelpResponse overview(Long id, AuthenticatedOperator actor) {
        return ChangeHelpGuide.explain(contexts.read(id,actor),"¿Qué tengo que hacer ahora?",false);
    }
    public ChangeHelpResponse ask(Long id, AuthenticatedOperator actor, String question, boolean simple) {
        var context=contexts.read(id,actor);
        boolean plain=simple || question.toLowerCase(java.util.Locale.ROOT)
                .matches("(?s).*(no entiendo|no lo entiendo|mas facil|más fácil|mas sencillo|más sencillo).*");
        var guide=ChangeHelpGuide.explain(context,question,plain);
        if (!explainer.available()) return guide;
        try {
            String answer=explainer.explain(context,question,guide);
            if (!DeepSeekChangeHelpExplainer.consistentExplanation(answer,guide)) return guide;
            return new ChangeHelpResponse(guide.protocolVersion(),guide.workSessionId(),guide.changeKey(),
                    guide.sourceRevision(),guide.observedAt(),guide.stage(),answer,guide.simpleAnswer(),
                    guide.facts(),guide.nextAction(),guide.suggestedQuestions(),"RECORDED_STATE_WITH_AI_EXPLANATION");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return guide;
        } catch (Exception unavailable) {
            return guide;
        }
    }
}
