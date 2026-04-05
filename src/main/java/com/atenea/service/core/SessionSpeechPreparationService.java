package com.atenea.service.core;

import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.service.mobile.MobileSessionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SessionSpeechPreparationService {

    private static final int BRIEF_MAX_LENGTH = 700;
    private static final int FULL_MAX_LENGTH = 3500;

    private final MobileSessionService mobileSessionService;

    public SessionSpeechPreparationService(MobileSessionService mobileSessionService) {
        this.mobileSessionService = mobileSessionService;
    }

    public SessionSpeechPreparationResult prepareLatestResponse(Long sessionId, SessionSpeechMode mode) {
        MobileSessionSummaryResponse summary = mobileSessionService.getSessionSummary(sessionId);
        return switch (mode) {
            case BRIEF -> prepareBrief(summary);
            case FULL -> prepareFull(summary);
        };
    }

    private SessionSpeechPreparationResult prepareBrief(MobileSessionSummaryResponse summary) {
        List<String> sectionsUsed = new ArrayList<>();
        List<String> sentences = new ArrayList<>();
        WorkSessionConversationViewResponse conversation = summary.conversation();
        WorkSessionViewResponse view = conversation.view();
        MobileSessionInsightsResponse insights = summary.insights();
        String source = resolveBestFullSource(summary);

        String latestProgress = normalizeSentence(insights == null ? null : insights.latestProgress());
        if (latestProgress != null && !looksLikeNoProgress(latestProgress)) {
            sectionsUsed.add("latestProgress");
            sentences.add("Punto actual: " + latestProgress);
        }

        String blocker = normalizeBlocker(insights == null ? null : insights.currentBlocker());
        if (blocker != null) {
            sectionsUsed.add("currentBlocker");
            sentences.add(blocker);
        }

        String nextStep = normalizeSentence(insights == null ? null : insights.nextStepRecommended());
        if (nextStep != null) {
            sectionsUsed.add("nextStepRecommended");
            sentences.add("Siguiente paso: " + nextStep);
        }

        String touchedFiles = summarizeTouchedFiles(source);
        if (touchedFiles != null) {
            sectionsUsed.add("touchedFiles");
            sentences.add("Archivos tocados: " + touchedFiles + ".");
        }

        String verification = extractSectionForSpeech(source, "Verificación", "Validation");
        if (verification != null) {
            sectionsUsed.add("verification");
            sentences.add("Verificación: " + verification + ".");
        }

        if (view.runInProgress()) {
            sectionsUsed.add("runInProgress");
            sentences.add("Hay una ejecución en curso.");
        }

        if (sentences.isEmpty()) {
            String fallback = summarizePlainText(resolveBestSource(summary), true);
            if (fallback == null) {
                throw new CoreVoiceUnavailableException(
                        "Atenea Core speech synthesis is not available for the latest session response");
            }
            return new SessionSpeechPreparationResult(
                    fallback,
                    SessionSpeechMode.BRIEF,
                    fallback.length() >= BRIEF_MAX_LENGTH,
                    List.of("fallback"));
        }

        String text = joinSentences(sentences);
        boolean truncated = false;
        if (text.length() > BRIEF_MAX_LENGTH) {
            text = truncateAtBoundary(text, BRIEF_MAX_LENGTH);
            truncated = true;
        }
        return new SessionSpeechPreparationResult(text, SessionSpeechMode.BRIEF, truncated, List.copyOf(sectionsUsed));
    }

    private SessionSpeechPreparationResult prepareFull(MobileSessionSummaryResponse summary) {
        String source = resolveBestFullSource(summary);
        String prepared = normalizeFullText(source);
        if (prepared == null) {
            throw new CoreVoiceUnavailableException(
                    "Atenea Core speech synthesis is not available for the latest session response");
        }
        boolean truncated = false;
        if (prepared.length() > FULL_MAX_LENGTH) {
            prepared = truncateAtBoundary(prepared, FULL_MAX_LENGTH);
            truncated = true;
        }
        return new SessionSpeechPreparationResult(
                prepared,
                SessionSpeechMode.FULL,
                truncated,
                List.of("sessionTurn"));
    }

    private String resolveBestSource(MobileSessionSummaryResponse summary) {
        WorkSessionConversationViewResponse conversation = summary.conversation();
        WorkSessionViewResponse view = conversation.view();
        if (view.lastAgentResponse() != null && !view.lastAgentResponse().isBlank()) {
            return view.lastAgentResponse();
        }
        for (int index = conversation.recentTurns().size() - 1; index >= 0; index--) {
            SessionTurnResponse turn = conversation.recentTurns().get(index);
            if (turn.actor() == SessionTurnActor.CODEX || turn.actor() == SessionTurnActor.ATENEA) {
                if (turn.messageText() != null && !turn.messageText().isBlank()) {
                    return turn.messageText();
                }
            }
        }
        return view.latestRun() == null ? null : view.latestRun().outputSummary();
    }

    private String resolveBestFullSource(MobileSessionSummaryResponse summary) {
        WorkSessionConversationViewResponse conversation = summary.conversation();
        for (int index = conversation.recentTurns().size() - 1; index >= 0; index--) {
            SessionTurnResponse turn = conversation.recentTurns().get(index);
            if ((turn.actor() == SessionTurnActor.CODEX || turn.actor() == SessionTurnActor.ATENEA)
                    && turn.messageText() != null
                    && !turn.messageText().isBlank()) {
                return turn.messageText();
            }
        }
        return resolveBestSource(summary);
    }

    private String normalizeBlocker(MobileSessionBlockerResponse blocker) {
        if (blocker == null) {
            return null;
        }
        String summary = normalizeSentence(blocker.summary());
        if (summary == null || looksLikeNoBlocker(summary)) {
            return null;
        }
        if ("BUSINESS".equalsIgnoreCase(blocker.category())) {
            return "Bloqueo de negocio: " + summary;
        }
        if ("TECHNICAL".equalsIgnoreCase(blocker.category())) {
            return "Bloqueo técnico: " + summary;
        }
        return "Bloqueo actual: " + summary;
    }

    private boolean looksLikeNoBlocker(String value) {
        String normalized = value.toLowerCase();
        return normalized.contains("sin bloqueo activo")
                || normalized.contains("no hay bloqueo activo")
                || normalized.contains("sin bloqueos");
    }

    private boolean looksLikeNoProgress(String value) {
        String normalized = value.toLowerCase();
        return normalized.contains("no hay un avance resumido disponible")
                || normalized.contains("no hay avance resumido");
    }

    private String normalizeFullText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> sections = new ArrayList<>();

        addSection(sections, "Punto actual", extractSectionForSpeech(value, "Punto actual", "Estado actual"));
        addSection(sections, "Qué he encontrado", extractSectionForSpeech(value, "Qué he encontrado"));
        addSection(sections, "Qué he hecho", extractSectionForSpeech(value, "Qué he hecho"));

        String blocker = extractSectionForSpeech(value, "Bloqueo actual", "Bloqueos", "Bloqueadores", "Riesgos");
        if (blocker != null && !looksLikeNoBlocker(blocker)) {
            addSection(sections, "Bloqueo actual", blocker);
        }

        addSection(
                sections,
                "Siguiente paso recomendado",
                extractSectionForSpeech(value, "Siguiente paso recomendado", "Siguiente paso", "Next step recommended", "Next step"));
        addSection(sections, "Verificación", extractSectionForSpeech(value, "Verificación", "Validation"));

        if (!sections.isEmpty()) {
            return joinSentences(sections);
        }

        String fallback = sanitizeFreeformSpeechText(value);
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private String summarizePlainText(String value, boolean singleSentence) {
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
        if (singleSentence) {
            int sentenceBoundary = normalized.indexOf(". ");
            if (sentenceBoundary > 0) {
                normalized = normalized.substring(0, sentenceBoundary + 1).trim();
            }
        }
        normalized = normalized.replaceFirst("[.]+$", "").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeSentence(String value) {
        String normalized = summarizePlainText(value, true);
        if (normalized == null) {
            return null;
        }
        if (normalized.endsWith(".")) {
            return normalized;
        }
        return normalized + ".";
    }

    private void addSection(List<String> sections, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sections.add(label + ": " + content);
    }

    private String extractSectionForSpeech(String value, String... headings) {
        for (String heading : headings) {
            String extracted = extractSection(value, heading);
            String sanitized = sanitizeSectionContent(extracted);
            if (sanitized != null) {
                return sanitized;
            }
        }
        return null;
    }

    private String extractSection(String value, String heading) {
        String pattern = "(?is)##\\s*" + Pattern.quote(heading) + "\\s*(.+?)(?=\\n##\\s+|\\z)";
        var matcher = Pattern.compile(pattern).matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String sanitizeSectionContent(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = value
                .replace("\r", "")
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^\\s*>\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "")
                .replaceAll("(?m)^.*?/workspace/.*$", " ")
                .replaceAll("(?m)^.*?python3 -m http\\.server.*$", " ")
                .replaceAll("(?m)^.*?http://localhost:8000/.*$", " ")
                .replaceAll("\\b/[^\\s)]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isBlank()) {
            return null;
        }
        String sentence = summarizePlainText(sanitized, false);
        if (sentence == null) {
            return null;
        }
        String lower = sentence.toLowerCase();
        if (lower.startsWith("archivos relevantes")
                || lower.startsWith("comandos útiles")
                || lower.startsWith("ruta actual del proyecto")) {
            return null;
        }
        return sentence;
    }

    private String sanitizeFreeformSpeechText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = value
                .replaceAll("(?s)```.*?```", " He preparado cambios en código y el detalle está disponible en pantalla. ")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^\\s*#{1,6}\\s*([^\\n]+)\\s*$", "$1. ")
                .replaceAll("(?m)^\\s*>\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "")
                .replaceAll("(?m)^.*?/workspace/.*$", " ")
                .replaceAll("(?m)^.*?python3 -m http\\.server.*$", " ")
                .replaceAll("(?m)^.*?http://localhost:8000/.*$", " ")
                .replaceAll("\\b/[^\\s)]+", " ")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .replaceAll("\\s*\\.\\s*", ". ")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isBlank()) {
            return null;
        }
        return sanitized;
    }

    private String summarizeTouchedFiles(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String relevantSections = String.join("\n",
                firstNonBlank(extractSection(source, "Qué he hecho"), ""),
                firstNonBlank(extractSection(source, "Archivos relevantes"), ""),
                firstNonBlank(extractSection(source, "Qué he encontrado"), ""));
        if (relevantSections.isBlank()) {
            return null;
        }

        Set<String> labels = new LinkedHashSet<>();
        collectFileLabels(labels, relevantSections, "\\[([^\\]]+)]\\([^)]*\\)");
        collectFileLabels(labels, relevantSections, "`([^`]+\\.[A-Za-z0-9]+)`");
        collectFileLabels(labels, relevantSections, "([A-Za-z0-9._/-]+\\.[A-Za-z0-9]+)");

        List<String> compact = labels.stream()
                .map(this::compactFileLabel)
                .filter(label -> label != null && !label.isBlank())
                .distinct()
                .limit(3)
                .toList();
        if (compact.isEmpty()) {
            return null;
        }
        if (compact.size() == 1) {
            return compact.get(0);
        }
        if (compact.size() == 2) {
            return compact.get(0) + " y " + compact.get(1);
        }
        return compact.get(0) + ", " + compact.get(1) + " y " + compact.get(2);
    }

    private void collectFileLabels(Set<String> labels, String source, String pattern) {
        var matcher = Pattern.compile(pattern).matcher(source);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String candidate = matcher.group(group);
                if (candidate != null && !candidate.isBlank()) {
                    labels.add(candidate);
                    break;
                }
            }
        }
    }

    private String compactFileLabel(String raw) {
        String normalized = raw
                .replace("`", "")
                .replaceAll("#L\\d+.*$", "")
                .replaceAll("\\?.*$", "")
                .trim();
        int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slashIndex >= 0 && slashIndex < normalized.length() - 1) {
            normalized = normalized.substring(slashIndex + 1);
        }
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (!normalized.contains(".")) {
            return null;
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String joinSentences(List<String> sentences) {
        return sentences.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::ensureSentenceEnding)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String ensureSentenceEnding(String value) {
        if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) {
            return value;
        }
        return value + ".";
    }

    private String truncateAtBoundary(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String shortened = value.substring(0, Math.max(0, maxLength - 3)).trim();
        int sentenceBoundary = Math.max(shortened.lastIndexOf(". "), shortened.lastIndexOf(": "));
        if (sentenceBoundary > 80) {
            shortened = shortened.substring(0, sentenceBoundary + 1).trim();
        }
        return shortened + "...";
    }
}
