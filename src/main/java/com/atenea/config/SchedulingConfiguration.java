package com.atenea.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@Profile("!retained-draft-recovery-command")
public class SchedulingConfiguration {
    // Preserve the ordinary scheduler. A slow GitHub/release transport must
    // never hold up notification dispatch or the five-minute health monitor.
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("atenea-scheduled-"); }
    @Bean(name = "mobileDeliveryScheduler")
    public ThreadPoolTaskScheduler mobileDeliveryScheduler() { return scheduler("atenea-delivery-"); }
    private ThreadPoolTaskScheduler scheduler(String name) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(name);
        return scheduler;
    }
}
