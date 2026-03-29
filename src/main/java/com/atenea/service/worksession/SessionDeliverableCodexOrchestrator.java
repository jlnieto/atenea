package com.atenea.service.worksession;

import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import org.springframework.stereotype.Service;

@Service
public class SessionDeliverableCodexOrchestrator {

    private static final String WORK_TICKET_PROMPT = """
            You are generating a professional work ticket for a completed repository work session.

            Return the final answer in disciplined Markdown.

            Requirements:
            - write in the same language as the session content when it is clear, otherwise use Spanish
            - be factual and auditable
            - do not invent work that is not supported by the snapshot
            - if information is uncertain, state that explicitly
            - optimize for client justification and internal traceability

            Use this structure when it applies:
            # Work Ticket
            ## Session
            ## Completed Work
            ## Repository Delivery
            ## Validation Evidence
            ## Risks Or Follow-ups

            Keep each section concise and specific.

            Session evidence snapshot as JSON:
            """;

    private static final String WORK_BREAKDOWN_PROMPT = """
            You are generating a professional work breakdown for a completed repository work session.

            Return the final answer in disciplined Markdown.

            Requirements:
            - write in the same language as the session content when it is clear, otherwise use Spanish
            - be specific, auditable and structured
            - do not invent work that is not supported by the snapshot
            - separate completed work from follow-ups or uncertainty
            - optimize for later client reporting

            Use this structure when it applies:
            # Work Breakdown
            ## Executive Summary
            ## Completed Work Items
            ## Technical Changes
            ## Validation Performed
            ## Risks And Follow-ups

            Prefer compact bullet lists when enumerating work items.

            Session evidence snapshot as JSON:
            """;

    private static final String PRICE_ESTIMATE_PROMPT = """
            You are generating an internal price estimate for a completed repository work session.

            Return two sections in this exact order:
            1. a Markdown report between markers PRICE_ESTIMATE_MARKDOWN_START and PRICE_ESTIMATE_MARKDOWN_END
            2. a JSON object between markers PRICE_ESTIMATE_JSON_START and PRICE_ESTIMATE_JSON_END

            This estimate is for the operator, not for direct customer delivery.

            Requirements:
            - use the pricing policy embedded in the snapshot as binding input
            - do not invent pricing rules outside the snapshot
            - produce a competitive fixed-price recommendation for Spain
            - the result must be base imponible without IVA
            - include an estimated internal equivalent-hours rationale
            - include a price range and a single recommended final price
            - include assumptions and exclusions explicitly
            - if evidence is weak or incomplete, say so

            Markdown structure:
            # Price Estimate
            ## Internal Scope Read
            ## Equivalent Hours
            ## Price Range
            ## Recommended Fixed Price
            ## Assumptions
            ## Exclusions
            ## Confidence Notes

            Keep the estimate commercially usable and internally auditable.

            The JSON object must contain these fields:
            - currency: string
            - baseHourlyRate: number
            - equivalentHours: number
            - minimumPrice: number
            - recommendedPrice: number
            - maximumPrice: number
            - commercialPositioning: string
            - riskLevel: string
            - confidence: string
            - assumptions: array of strings
            - exclusions: array of strings

            recommendedPrice must be between minimumPrice and maximumPrice.

            Session evidence snapshot as JSON:
            """;

    private final CodexAppServerClient codexAppServerClient;

    public SessionDeliverableCodexOrchestrator(CodexAppServerClient codexAppServerClient) {
        this.codexAppServerClient = codexAppServerClient;
    }

    public String generateWorkTicket(String repoPath, String snapshotJson) throws Exception {
        CodexAppServerExecutionResult result = codexAppServerClient.execute(
                new CodexAppServerExecutionRequest(repoPath, WORK_TICKET_PROMPT + snapshotJson));

        if (result.status() != CodexAppServerExecutionResult.Status.COMPLETED) {
            throw new IllegalStateException(firstNonBlank(
                    result.errorMessage(),
                    "Codex did not complete WORK_TICKET generation"));
        }
        return firstNonBlank(result.finalAnswer(), result.outputSummary());
    }

    public String generateWorkBreakdown(String repoPath, String snapshotJson) throws Exception {
        CodexAppServerExecutionResult result = codexAppServerClient.execute(
                new CodexAppServerExecutionRequest(repoPath, WORK_BREAKDOWN_PROMPT + snapshotJson));

        if (result.status() != CodexAppServerExecutionResult.Status.COMPLETED) {
            throw new IllegalStateException(firstNonBlank(
                    result.errorMessage(),
                    "Codex did not complete WORK_BREAKDOWN generation"));
        }
        return firstNonBlank(result.finalAnswer(), result.outputSummary());
    }

    public PriceEstimateGenerationResult generatePriceEstimate(String repoPath, String snapshotJson) throws Exception {
        CodexAppServerExecutionResult result = codexAppServerClient.execute(
                new CodexAppServerExecutionRequest(repoPath, PRICE_ESTIMATE_PROMPT + snapshotJson));

        if (result.status() != CodexAppServerExecutionResult.Status.COMPLETED) {
            throw new IllegalStateException(firstNonBlank(
                    result.errorMessage(),
                    "Codex did not complete PRICE_ESTIMATE generation"));
        }
        String output = firstNonBlank(result.finalAnswer(), result.outputSummary());
        return parsePriceEstimateOutput(output);
    }

    private PriceEstimateGenerationResult parsePriceEstimateOutput(String output) {
        String markdown = extractRequiredSection(output, "PRICE_ESTIMATE_MARKDOWN_START", "PRICE_ESTIMATE_MARKDOWN_END");
        String json = extractRequiredSection(output, "PRICE_ESTIMATE_JSON_START", "PRICE_ESTIMATE_JSON_END");
        return new PriceEstimateGenerationResult(markdown.trim(), json.trim());
    }

    private String extractRequiredSection(String output, String startMarker, String endMarker) {
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("Codex returned empty PRICE_ESTIMATE output");
        }
        int start = output.indexOf(startMarker);
        int end = output.indexOf(endMarker);
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("Codex returned PRICE_ESTIMATE output without required structured markers");
        }
        String section = output.substring(start + startMarker.length(), end).trim();
        if (section.isBlank()) {
            throw new IllegalStateException("Codex returned empty section for " + startMarker);
        }
        return section;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record PriceEstimateGenerationResult(
            String markdown,
            String contentJson
    ) {
    }
}
