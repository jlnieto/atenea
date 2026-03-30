package com.atenea.mobilepush;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MobilePushProperties.class)
public class MobilePushConfiguration {

    @Bean
    public HttpClient mobilePushHttpClient() {
        return HttpClient.newHttpClient();
    }
}
