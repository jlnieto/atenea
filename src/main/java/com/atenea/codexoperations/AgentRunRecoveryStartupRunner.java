package com.atenea.codexoperations;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Order(100)
@Profile("!retained-draft-recovery-command")
public class AgentRunRecoveryStartupRunner implements ApplicationRunner {

    private final AgentRunRecoveryCoordinator coordinator;

    public AgentRunRecoveryStartupRunner(AgentRunRecoveryCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        coordinator.resumePending();
    }
}
