package com.atenea.codexappserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties({CodexAppServerProperties.class, RescueCodexAppServerProperties.class})
public class CodexAppServerConfiguration {

    @Bean
    @Primary
    CodexAppServerClient codexAppServerClient(
            ObjectMapper objectMapper,
            @Qualifier("atenea.codex-app-server-com.atenea.codexappserver.CodexAppServerProperties")
            CodexAppServerProperties properties
    ) {
        return new CodexAppServerClient(objectMapper, properties);
    }

    @Bean
    CodexAppServerClient rescueCodexAppServerClient(
            ObjectMapper objectMapper,
            RescueCodexAppServerProperties properties
    ) {
        return new CodexAppServerClient(objectMapper, properties.toCodexAppServerProperties());
    }
}
