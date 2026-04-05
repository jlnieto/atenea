package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileSessionActionsResponse;
import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.worksession.SessionTurnActor;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class MobileSessionInsightsService {

    public MobileSessionInsightsResponse buildInsights(
            WorkSessionConversationViewResponse conversation,
            MobileSessionActionsResponse actions
    ) {
        WorkSessionViewResponse view = conversation.view();
        return new MobileSessionInsightsResponse(
                latestProgressSummary(view, conversation),
                currentBlockerSummary(view, conversation),
                nextStepSummary(view, conversation, actions)
        );
    }

    private String latestProgressSummary(
            WorkSessionViewResponse view,
            WorkSessionConversationViewResponse conversation
    ) {
        String latestAgentResponse = extractSectionSentence(
                view.lastAgentResponse(),
                "Punto actual",
                "Estado actual",
                "Que he hecho",
                "Qué he hecho",
                "Ultimo avance",
                "Último avance");
        if (latestAgentResponse != null) {
            return latestAgentResponse;
        }

        latestAgentResponse = spokenExcerpt(view.lastAgentResponse());
        if (latestAgentResponse != null) {
            return latestAgentResponse;
        }

        for (int index = conversation.recentTurns().size() - 1; index >= 0; index--) {
            SessionTurnResponse turn = conversation.recentTurns().get(index);
            if (turn.actor() == SessionTurnActor.CODEX || turn.actor() == SessionTurnActor.ATENEA) {
                String spokenTurn = spokenExcerpt(turn.messageText());
                if (spokenTurn != null) {
                    return spokenTurn;
                }
            }
        }

        if (view.latestRun() != null) {
            String outputSummary = spokenExcerpt(view.latestRun().outputSummary());
            if (outputSummary != null) {
                return outputSummary;
            }
        }

        return "No hay un avance resumido disponible";
    }

    private MobileSessionBlockerResponse currentBlockerSummary(
            WorkSessionViewResponse view,
            WorkSessionConversationViewResponse conversation
    ) {
        String blocker = spokenExcerpt(view.lastError());
        if (blocker != null) {
            return toBlocker(blocker);
        }

        blocker = firstNonBlank(
                extractSectionSentence(view.lastAgentResponse(), "Bloqueo actual", "Bloqueos", "Bloqueadores", "Riesgos"),
                latestSectionFromTurns(conversation, "Bloqueo actual", "Bloqueos", "Bloqueadores", "Riesgos"),
                view.latestRun() == null ? null
                        : extractSectionSentence(view.latestRun().outputSummary(), "Bloqueo actual", "Bloqueos", "Bloqueadores", "Riesgos"));
        if (blocker != null) {
            return toBlocker(blocker);
        }

        blocker = spokenExcerpt(view.session().closeBlockedReason());
        if (blocker != null) {
            return toBlocker(blocker);
        }

        if (view.runInProgress()) {
            return new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo; la ejecución sigue en curso");
        }

        return new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo");
    }

    private String nextStepSummary(
            WorkSessionViewResponse view,
            WorkSessionConversationViewResponse conversation,
            MobileSessionActionsResponse actions
    ) {
        String nextStep = firstNonBlank(
                extractSectionSentence(view.lastAgentResponse(), "Siguiente paso recomendado", "Siguiente paso", "Next step recommended", "Next step"),
                latestSectionFromTurns(conversation, "Siguiente paso recomendado", "Siguiente paso", "Next step recommended", "Next step"),
                view.latestRun() == null ? null
                        : extractSectionSentence(view.latestRun().outputSummary(), "Siguiente paso recomendado", "Siguiente paso", "Next step recommended", "Next step"));
        if (nextStep != null) {
            return nextStep;
        }

        if (view.runInProgress()) {
            return "Esperar a que termine la ejecución actual";
        }
        if (actions.canPublish()) {
            return "Publicar la pull request de la sesión";
        }
        if (actions.canSyncPullRequest()) {
            return "Sincronizar el estado de la pull request";
        }
        if (actions.canGenerateDeliverables()) {
            return "Generar los entregables pendientes de la sesión";
        }
        if (actions.canCreateTurn()) {
            return "Enviar la siguiente instrucción a Codex";
        }
        return "Revisar manualmente la sesión actual";
    }

    private MobileSessionBlockerResponse toBlocker(String value) {
        String normalized = spokenExcerpt(value);
        if (normalized == null || looksLikeNoBlocker(normalized)) {
            return new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo");
        }

        String clean = stripLeadingBlockerLabel(normalized);
        return new MobileSessionBlockerResponse(classifyBlockerCategory(clean), clean);
    }

    private String latestSectionFromTurns(
            WorkSessionConversationViewResponse conversation,
            String... headings
    ) {
        for (int index = conversation.recentTurns().size() - 1; index >= 0; index--) {
            SessionTurnResponse turn = conversation.recentTurns().get(index);
            if (turn.actor() == SessionTurnActor.CODEX || turn.actor() == SessionTurnActor.ATENEA) {
                String section = extractSectionSentence(turn.messageText(), headings);
                if (section != null) {
                    return section;
                }
            }
        }
        return null;
    }

    private String extractSectionSentence(String value, String... headings) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String heading : headings) {
            String extracted = extractMarkdownSection(value, heading);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
    }

    private String extractMarkdownSection(String value, String heading) {
        String regex = "(?is)(?:^|\\n)\\s*#{1,6}\\s*" + Pattern.quote(heading)
                + "\\s*(.+?)(?=(?:\\n\\s*#{1,6}\\s)|\\z)";
        Matcher matcher = Pattern.compile(regex).matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return normalizeRecommendation(spokenExcerpt(matcher.group(1)));
    }

    private String spokenExcerpt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replace("```", " ")
                .replace('\n', '.')
                .replace('\r', '.')
                .replace('`', ' ')
                .replaceAll("#{1,6}\\s*", " ")
                .replaceAll("\\s*\\.\\s*", ". ")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized
                .replaceFirst("(?i)^punto actual\\.?\\s*", "")
                .replaceFirst("(?i)^estado actual\\.?\\s*", "")
                .replaceFirst("[.]+$", "")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        int sentenceBoundary = normalized.indexOf(". ");
        if (sentenceBoundary > 0) {
            normalized = normalized.substring(0, sentenceBoundary + 1).trim();
        }
        normalized = normalized.replaceFirst("[.]+$", "").trim();
        if (normalized.length() > 220) {
            return normalized.substring(0, 217).trim() + "...";
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeRecommendation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value
                .replaceFirst("(?i)^el siguiente paso (útil|recomendado|logico|lógico) es\\s+", "")
                .replaceFirst("(?i)^siguiente paso (útil|recomendado|logico|lógico):?\\s*", "")
                .replaceFirst("(?i)^opciones razonables:?\\s*", "")
                .replaceFirst("(?i)^recomendación:?\\s*", "")
                .trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private boolean looksLikeNoBlocker(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("sin bloqueo")
                || normalized.contains("no hay bloqueo")
                || normalized.contains("no blocker");
    }

    private String stripLeadingBlockerLabel(String value) {
        return value
                .replaceFirst("(?i)^bloqueo actual:?\\s*", "")
                .replaceFirst("(?i)^bloqueo técnico:?\\s*", "")
                .replaceFirst("(?i)^bloqueo tecnico:?\\s*", "")
                .replaceFirst("(?i)^bloqueo de negocio:?\\s*", "")
                .replaceFirst("(?i)^técnico:?\\s*", "")
                .replaceFirst("(?i)^tecnico:?\\s*", "")
                .replaceFirst("(?i)^de negocio:?\\s*", "")
                .trim();
    }

    private String classifyBlockerCategory(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "test",
                "tests",
                "build",
                "compile",
                "compil",
                "error",
                "exception",
                "stacktrace",
                "ssl",
                "tls",
                "timeout",
                "docker",
                "network",
                "api",
                "endpoint",
                "repositorio",
                "branch",
                "merge conflict",
                "github actions",
                "continuous integration",
                "pipeline",
                "runtime",
                "nullpointer")) {
            return "TECHNICAL";
        }
        if (containsAny(normalized,
                "cliente",
                "aprob",
                "validación",
                "validacion",
                "feedback",
                "copy",
                "contenido",
                "diseño",
                "diseno",
                "alcance",
                "presupuesto",
                "factur",
                "pricing",
                "comercial",
                "decisión",
                "decision",
                "revisión",
                "revision")) {
            return "BUSINESS";
        }
        return "TECHNICAL";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
