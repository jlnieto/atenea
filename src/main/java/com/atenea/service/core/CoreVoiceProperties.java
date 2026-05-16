package com.atenea.service.core;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.core.voice")
public class CoreVoiceProperties {

    private boolean enabled = false;
    private URI apiBaseUrl = URI.create("https://api.openai.com");
    private String apiKey;
    private String transcriptionModel = "gpt-4o-mini-transcribe";
    private String speechModel = "gpt-4o-mini-tts";
    private String speechVoice = "marin";
    private String speechInstructions = """
            Speak in clear, natural Castilian Spanish from Spain.
            Read every word in the input. Do not summarize, paraphrase, skip, or shorten the text.
            Preserve final greetings, sign-offs, informal closings, and trailing sentences.
            Use a neutral Spain Spanish accent, not Latin American Spanish.
            Prefer peninsular Spanish pronunciation and intonation.
            Avoid Latin American cadence, vowel coloring, and regionalisms.
            Keep a warm, professional tone with a natural pace.
            Avoid English phonetics for Spanish sentences.
            Pronounce project and product names naturally in Spanish when possible.
            Use short pauses between sections so the result sounds conversational, not robotic.
            Do not over-emphasize punctuation, Markdown markers, file paths, or code-like tokens.
            """;
    private String speechFormat = "mp3";
    private double speechSpeed = 1.05d;
    private String realtimeModel = "gpt-realtime";
    private String realtimeVoice = "marin";
    private double realtimeSpeed = 1.05d;
    private String realtimeInstructions = """
            Eres Atenea, una asistente operativa por voz.
            Hablas en espanol de Espana, de tu, con tono cercano y profesional.
            Responde primero con resumen cuando haya mucho contenido.
            No ejecutes acciones sensibles sin confirmacion explicita.
            No inventes proyectos, worksessions, servidores, emails, tareas, ramas ni respuestas de Codex.
            Si no tienes un dato real cargado en el contexto de sesion, dilo claramente.
            """;
    private String prompt = """
            Transcribe the operator voice command faithfully.
            Preserve project names, branch names, repository names, pull request references, and delivery terms.
            The command may be in Spanish or English.
            """;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    private long maxUploadBytes = 10_000_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(URI apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTranscriptionModel() {
        return transcriptionModel;
    }

    public void setTranscriptionModel(String transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    public String getSpeechModel() {
        return speechModel;
    }

    public void setSpeechModel(String speechModel) {
        this.speechModel = speechModel;
    }

    public String getSpeechVoice() {
        return speechVoice;
    }

    public void setSpeechVoice(String speechVoice) {
        this.speechVoice = speechVoice;
    }

    public String getSpeechInstructions() {
        return speechInstructions;
    }

    public void setSpeechInstructions(String speechInstructions) {
        this.speechInstructions = speechInstructions;
    }

    public String getSpeechFormat() {
        return speechFormat;
    }

    public void setSpeechFormat(String speechFormat) {
        this.speechFormat = speechFormat;
    }

    public double getSpeechSpeed() {
        return speechSpeed;
    }

    public void setSpeechSpeed(double speechSpeed) {
        this.speechSpeed = speechSpeed;
    }

    public String getRealtimeModel() {
        return realtimeModel;
    }

    public void setRealtimeModel(String realtimeModel) {
        this.realtimeModel = realtimeModel;
    }

    public String getRealtimeVoice() {
        return realtimeVoice;
    }

    public void setRealtimeVoice(String realtimeVoice) {
        this.realtimeVoice = realtimeVoice;
    }

    public double getRealtimeSpeed() {
        return realtimeSpeed;
    }

    public void setRealtimeSpeed(double realtimeSpeed) {
        this.realtimeSpeed = realtimeSpeed;
    }

    public String getRealtimeInstructions() {
        return realtimeInstructions;
    }

    public void setRealtimeInstructions(String realtimeInstructions) {
        this.realtimeInstructions = realtimeInstructions;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }
}
