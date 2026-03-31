package com.atenea.service.core;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CoreLlmProperties.class)
public class CoreLlmConfiguration {
}
