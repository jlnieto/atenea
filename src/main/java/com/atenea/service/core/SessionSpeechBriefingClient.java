package com.atenea.service.core;

public interface SessionSpeechBriefingClient {

    boolean supports(String provider);

    SessionSpeechBriefingResult createBriefing(SessionSpeechBriefingRequest request);
}
