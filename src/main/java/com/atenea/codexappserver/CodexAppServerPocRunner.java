package com.atenea.codexappserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("codex-app-server-poc")
public class CodexAppServerPocRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexAppServerPocRunner.class);

    private final CodexAppServerClient client;
    private final CodexAppServerProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    public CodexAppServerPocRunner(
            CodexAppServerClient client,
            CodexAppServerProperties properties,
            ConfigurableApplicationContext applicationContext) {
        this.client = client;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            log.info("codex-app-server-poc starting url={} cwd={} prompt={}",
                    properties.getUrl(),
                    properties.getCwd(),
                    preview(properties.getPrompt()));

            CodexAppServerClient.CodexAppServerPocResult result = client.runProofOfConcept();
            log.info(
                    "codex-app-server-poc summary threadId={} turnId={} status={} finalAnswer={} commentaryPreview={} error={}",
                    result.threadId(),
                    result.turnId(),
                    result.status(),
                    preview(result.finalAnswerPreview()),
                    preview(result.commentaryPreview()),
                    preview(result.errorMessage()));
        } catch (Exception exception) {
            exitCode = 1;
            log.error("codex-app-server-poc failed", exception);
        } finally {
            int finalExitCode = exitCode;
            int code = SpringApplication.exit(applicationContext, () -> finalExitCode);
            System.exit(code);
        }
    }

    private String preview(String value) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + " ...";
    }
}
