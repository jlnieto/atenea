package com.atenea.operations;

import com.atenea.api.worksession.RecoverDraftWorkSessionResponse;
import com.atenea.service.worksession.RetainedDraftRecoveryService;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile(RetainedDraftRecoveryCommand.PROFILE)
public class RetainedDraftRecoveryCommand implements ApplicationRunner {

    static final String PROFILE = "retained-draft-recovery-command";
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Logger log = LoggerFactory.getLogger(RetainedDraftRecoveryCommand.class);

    private final Environment environment;
    private final RetainedDraftRecoveryService recoveryService;
    private final ConfigurableApplicationContext applicationContext;

    public RetainedDraftRecoveryCommand(
            Environment environment,
            RetainedDraftRecoveryService recoveryService,
            ConfigurableApplicationContext applicationContext
    ) {
        this.environment = environment;
        this.recoveryService = recoveryService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute();
        int code = SpringApplication.exit(applicationContext, () -> exitCode);
        System.exit(code);
    }

    int execute() {
        try {
            long sessionId = positiveLong(required("atenea.operations.retained-draft-recovery.session-id"));
            UUID expectedRemoteSessionId = UUID.fromString(required(
                    "atenea.operations.retained-draft-recovery.expected-remote-session-id"));
            String expectedRetainedHead = commit(required(
                    "atenea.operations.retained-draft-recovery.expected-retained-head"));
            String expectedAcceptedCommit = commit(required(
                    "atenea.operations.retained-draft-recovery.expected-accepted-commit"));

            RecoverDraftWorkSessionResponse response = recoveryService.recoverExact(
                    sessionId,
                    expectedRemoteSessionId,
                    expectedRetainedHead,
                    expectedAcceptedCommit);
            if (!expectedRetainedHead.equals(response.retainedHead())
                    || !expectedAcceptedCommit.equals(response.acceptedCommit())
                    || response.valuesExposed()) {
                throw new IllegalStateException("The sanitized recovery result diverged from the exact authority");
            }

            log.info(
                    "retained draft recovery complete retainedSessionId={} replacementWorkSessionId={} "
                            + "retainedHead={} acceptedCommit={} fingerprintSha256={} staged={} unstaged={} "
                            + "untracked={} valuesExposed={}",
                    response.blockedSessionId(),
                    response.replacementSessionId(),
                    response.retainedHead(),
                    response.acceptedCommit(),
                    response.draftFingerprintSha256(),
                    response.stagedChangeCount(),
                    response.unstagedChangeCount(),
                    response.untrackedChangeCount(),
                    response.valuesExposed());
            return 0;
        } catch (Exception exception) {
            log.error("retained draft recovery failed closed: {}", exception.getMessage());
            return 1;
        }
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required exact recovery property: " + name);
        }
        return value.trim();
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("The retained WorkSession id must be positive");
        }
        return parsed;
    }

    private String commit(String value) {
        if (!COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected commits must be lowercase full SHA-1 values");
        }
        return value;
    }
}
