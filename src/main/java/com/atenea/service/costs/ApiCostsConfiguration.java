package com.atenea.service.costs;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApiCostsProperties.class)
public class ApiCostsConfiguration {

    @Bean
    HttpClient apiCostsHttpClient(ApiCostsProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getOpenai().getConnectTimeout())
                .build();
    }
}
