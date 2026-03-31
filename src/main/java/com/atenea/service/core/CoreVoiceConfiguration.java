package com.atenea.service.core;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CoreVoiceProperties.class)
public class CoreVoiceConfiguration {

    @Bean
    HttpClient coreVoiceHttpClient(CoreVoiceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }
}
