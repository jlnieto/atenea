package com.atenea.service.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.briefing")
public class SessionSpeechBriefingProperties {

    private boolean enabled = false;
    private String provider = "deepseek";
    private String model = "deepseek-v4-flash";
    private String promptVersion = "session-speech-briefing-v1";
    private int maxInputCharacters = 18_000;
    private int briefMaxOutputCharacters = 430;
    private int fullMaxOutputCharacters = 3_500;
    private int maxOutputTokens = 900;
    private double temperature = 0.1d;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getMaxInputCharacters() {
        return maxInputCharacters;
    }

    public void setMaxInputCharacters(int maxInputCharacters) {
        this.maxInputCharacters = maxInputCharacters;
    }

    public int getBriefMaxOutputCharacters() {
        return briefMaxOutputCharacters;
    }

    public void setBriefMaxOutputCharacters(int briefMaxOutputCharacters) {
        this.briefMaxOutputCharacters = briefMaxOutputCharacters;
    }

    public int getFullMaxOutputCharacters() {
        return fullMaxOutputCharacters;
    }

    public void setFullMaxOutputCharacters(int fullMaxOutputCharacters) {
        this.fullMaxOutputCharacters = fullMaxOutputCharacters;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
