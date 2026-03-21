package com.atenea.codexappserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CodexAppServerProperties.class)
public class CodexAppServerConfiguration {

    @Bean
    CodexAppServerClient codexAppServerClient(ObjectMapper objectMapper, CodexAppServerProperties properties) {
        return new CodexAppServerClient(objectMapper, properties);
    }
}
