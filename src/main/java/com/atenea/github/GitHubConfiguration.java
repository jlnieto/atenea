package com.atenea.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubConfiguration {

    @Bean
    GitHubClient gitHubClient(ObjectMapper objectMapper, GitHubProperties properties) {
        return new GitHubClient(objectMapper, properties);
    }
}
