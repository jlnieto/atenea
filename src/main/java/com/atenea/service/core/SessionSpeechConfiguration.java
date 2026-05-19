package com.atenea.service.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SessionSpeechBriefingProperties.class)
public class SessionSpeechConfiguration {
}
