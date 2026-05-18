package com.atenea.service.costs;

import com.atenea.deepseek.DeepSeekProperties;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ApiCostsProperties.class, DeepSeekProperties.class})
public class ApiCostsConfiguration {

    @Bean
    HttpClient apiCostsHttpClient(ApiCostsProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getOpenai().getConnectTimeout())
                .build();
    }

    @Bean
    @Qualifier("deepSeekCostsHttpClient")
    HttpClient deepSeekCostsHttpClient(DeepSeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }
}
