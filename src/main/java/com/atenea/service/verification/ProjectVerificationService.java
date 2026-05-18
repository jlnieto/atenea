package com.atenea.service.verification;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.verification.ProjectVerificationRunEntity;
import com.atenea.persistence.verification.ProjectVerificationRunRepository;
import com.atenea.persistence.verification.ProjectVerificationStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.core.CoreInvalidContextException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectVerificationService {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration START_COMMAND_TIMEOUT = Duration.ofMinutes(8);
    private static final Duration HEALTH_STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int OUTPUT_SUMMARY_LIMIT = 3000;

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final ProjectVerificationRunRepository verificationRunRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ProjectVerificationService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            ProjectVerificationRunRepository verificationRunRepository,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.verificationRunRepository = verificationRunRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Transactional
    public ProjectVerificationResponse runVerification(Long projectId, Long workSessionId) {
        WorkSessionEntity workSession = null;
        if (workSessionId != null) {
            workSession = workSessionRepository.findWithProjectById(workSessionId)
                    .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: workSessionId"));
            if (projectId != null && !workSession.getProject().getId().equals(projectId)) {
                throw new CoreInvalidContextException("WorkSession does not belong to the requested project");
            }
            projectId = workSession.getProject().getId();
        }
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: projectId"));

        Instant startedAt = Instant.now();
        ProjectRuntimeContract contract = ProjectRuntimeContract.missing(resolveRepoPath(project.getRepoPath()));
        ProjectVerificationStatus status = ProjectVerificationStatus.BLOCKED;
        String decisionBrief = "No puedo validar el proyecto todavía.";
        String technicalSummary = "La verificación no llegó a completar un resultado específico.";
        String blockerType = null;
        String blockerSummary = null;
        String recommendedAction = null;
        List<ProjectVerificationTestResult> tests = new ArrayList<>();
        List<String> artifacts = List.of();
        boolean cleanupAfterVerification = false;
        ProjectRuntimeContract completedContract = contract;

        Optional<Path> contractPath = findContract(contract.repoPath());
        if (contractPath.isEmpty()) {
            status = ProjectVerificationStatus.BLOCKED;
            blockerType = "MISSING_RUNTIME_CONTRACT";
            blockerSummary = "El proyecto no declara ops/atenea-runtime.yml ni ops/atenea-runtime.json.";
            recommendedAction = "Añadir un contrato de runtime con health URL, comando de smoke browser y artefactos.";
            decisionBrief = "No puedo validar el proyecto todavía: falta el contrato operativo de runtime.";
            technicalSummary = "Repo resuelto en " + contract.repoPath()
                        + ", pero no existe un contrato ops/atenea-runtime compatible.";
        } else {
            contract = readContract(contractPath.get(), contract.repoPath());
            completedContract = contract;
            cleanupAfterVerification = contract.cleanupAfterVerification();
            artifacts = contract.artifactPaths();
            if (contract.testOnly()) {
                CommandResult command = runControlledCommand(
                        "project-test",
                        contract,
                        contract.testCommand(),
                        contract.commandTimeout());
                tests.add(new ProjectVerificationTestResult(
                        "project-test",
                        command.exitCode() == 0 ? "PASSED" : "FAILED",
                        command.exitCode(),
                        command.durationMillis(),
                        command.summary()));
                if (command.exitCode() == 0) {
                    status = ProjectVerificationStatus.PASSED;
                    decisionBrief = "Validación correcta: la suite canónica del proyecto ha pasado.";
                    technicalSummary = "Contrato test-only encontrado en " + contract.contractPath()
                            + ". testCommand terminó correctamente.";
                } else {
                    status = ProjectVerificationStatus.FAILED;
                    blockerType = "PROJECT_TEST_FAILED";
                    blockerSummary = command.summary();
                    recommendedAction = "Corregir la suite canónica del proyecto antes de continuar.";
                    decisionBrief = "No desplegar: la suite canónica del proyecto ha fallado.";
                    technicalSummary = "El testCommand terminó con código " + command.exitCode() + ".";
                }
                return persist(project, workSession, status, completedContract, decisionBrief, technicalSummary,
                        blockerType, blockerSummary, recommendedAction, tests, artifacts, startedAt);
            }
            HealthProbeResult health = probeHealth(contract);
            if (!health.ok() && contract.startCommand() != null) {
                CommandResult startResult = runControlledCommand(
                        "runtime-start",
                        contract,
                        contract.startCommand(),
                        START_COMMAND_TIMEOUT);
                tests.add(new ProjectVerificationTestResult(
                        "runtime-start",
                        startResult.exitCode() == 0 ? "PASSED" : "BLOCKED",
                        startResult.exitCode(),
                        startResult.durationMillis(),
                        startResult.summary()));
                if (startResult.exitCode() == 0) {
                    health = waitForHealth(contract);
                }
            }

            try {
                if (health.effectiveBaseUrl() != null) {
                    contract = contract.withBaseUrl(health.effectiveBaseUrl(), health.effectiveHealthUrl());
                    completedContract = contract;
                }
                tests.add(new ProjectVerificationTestResult(
                        "runtime-health",
                        health.ok() ? "PASSED" : "BLOCKED",
                        health.statusCode(),
                        health.durationMillis(),
                        health.summary()));

                if (!health.ok()) {
                    if (contract.testCommand() != null && health.effectiveHealthUrl() == null) {
                        CommandResult command = runControlledCommand(
                                "project-test",
                                contract,
                                contract.testCommand(),
                                contract.commandTimeout());
                        tests.add(new ProjectVerificationTestResult(
                                "project-test",
                                command.exitCode() == 0 ? "PASSED" : "FAILED",
                                command.exitCode(),
                                command.durationMillis(),
                                command.summary()));
                        if (command.exitCode() == 0) {
                            status = ProjectVerificationStatus.PASSED;
                            decisionBrief = "Validación correcta: la suite canónica del proyecto ha pasado.";
                            technicalSummary = "Contrato encontrado en " + contract.contractPath()
                                    + ". No hay runtime web declarado, pero testCommand terminó correctamente.";
                        } else {
                            status = ProjectVerificationStatus.FAILED;
                            blockerType = "PROJECT_TEST_FAILED";
                            blockerSummary = command.summary();
                            recommendedAction = "Corregir la suite canónica del proyecto antes de continuar.";
                            decisionBrief = "No desplegar: la suite canónica del proyecto ha fallado.";
                            technicalSummary = "El testCommand terminó con código " + command.exitCode() + ".";
                        }
                    } else {
                        status = ProjectVerificationStatus.BLOCKED;
                        blockerType = "RUNTIME_UNAVAILABLE";
                        blockerSummary = health.summary();
                        recommendedAction = runtimeUnavailableAction(contract);
                        decisionBrief = "No desplegar: el runtime del proyecto no está disponible para pruebas de navegador.";
                        technicalSummary = "Contrato encontrado en " + contract.contractPath()
                                + ". Health check falló: " + health.summary();
                    }
                } else {
                    boolean projectTestPassed = true;
                    if (contract.testCommand() != null) {
                        CommandResult command = runControlledCommand(
                                "project-test",
                                contract,
                                contract.testCommand(),
                                contract.commandTimeout());
                        tests.add(new ProjectVerificationTestResult(
                                "project-test",
                                command.exitCode() == 0 ? "PASSED" : "FAILED",
                                command.exitCode(),
                                command.durationMillis(),
                                command.summary()));
                        if (command.exitCode() != 0) {
                            projectTestPassed = false;
                            status = ProjectVerificationStatus.FAILED;
                            blockerType = "PROJECT_TEST_FAILED";
                            blockerSummary = command.summary();
                            recommendedAction = "Corregir la suite canónica del proyecto antes de ejecutar pruebas de navegador.";
                            decisionBrief = "No desplegar: la suite canónica del proyecto ha fallado.";
                            technicalSummary = "Health check correcto, pero testCommand terminó con código "
                                    + command.exitCode() + ".";
                        }
                    }
                    if (projectTestPassed) {
                        if (contract.browserTestCommand() == null) {
                            status = ProjectVerificationStatus.PASSED;
                            decisionBrief = "Runtime disponible. No hay smoke browser configurado, así que la validación queda limitada.";
                            technicalSummary = "Health check correcto, pero el contrato no define browserTestCommand.";
                        } else {
                            CommandResult command = runBrowserCommand(contract);
                            tests.add(new ProjectVerificationTestResult(
                                    "browser-smoke",
                                    command.exitCode() == 0 ? "PASSED" : "FAILED",
                                    command.exitCode(),
                                    command.durationMillis(),
                                    command.summary()));
                            if (command.exitCode() == 0) {
                                status = ProjectVerificationStatus.PASSED;
                                decisionBrief = "Validación correcta: runtime disponible y smoke browser pasado.";
                                technicalSummary = "Health check correcto y comando browser completado correctamente.";
                            } else {
                                status = ProjectVerificationStatus.FAILED;
                                blockerType = "BROWSER_TEST_FAILED";
                                blockerSummary = command.summary();
                                recommendedAction = "Revisar el informe de Playwright y corregir el fallo antes de desplegar.";
                                decisionBrief = "No desplegar: las pruebas de navegador han fallado.";
                                technicalSummary = "Health check correcto, pero el smoke browser terminó con código "
                                        + command.exitCode() + ".";
                            }
                        }
                    }
                }
            } finally {
                if (cleanupAfterVerification && contract.cleanupCommand() != null) {
                    CommandResult cleanupResult = runControlledCommand(
                            "runtime-cleanup",
                            contract,
                            contract.cleanupCommand(),
                            START_COMMAND_TIMEOUT);
                    tests.add(new ProjectVerificationTestResult(
                            "runtime-cleanup",
                            cleanupResult.exitCode() == 0 ? "PASSED" : "FAILED",
                            cleanupResult.exitCode(),
                            cleanupResult.durationMillis(),
                            cleanupResult.summary()));
                }
            }
        }

        Instant finishedAt = Instant.now();
        ProjectVerificationRunEntity entity = new ProjectVerificationRunEntity();
        entity.setProject(project);
        entity.setWorkSession(workSession);
        entity.setStatus(status);
        entity.setRuntimeContractPath(completedContract.contractPath());
        entity.setRuntimeProfile(completedContract.runtimeProfile());
        entity.setBaseUrl(completedContract.baseUrl());
        entity.setDecisionBrief(decisionBrief);
        entity.setTechnicalSummary(technicalSummary);
        entity.setBlockerType(blockerType);
        entity.setBlockerSummary(blockerSummary);
        entity.setRecommendedAction(recommendedAction);
        entity.setTestsJson(writeJson(tests));
        entity.setArtifactsJson(writeJson(artifacts));
        entity.setStartedAt(startedAt);
        entity.setFinishedAt(finishedAt);
        entity.setCreatedAt(finishedAt);
        ProjectVerificationRunEntity saved = verificationRunRepository.save(entity);
        return toResponse(saved, tests, artifacts);
    }

    private Optional<Path> findContract(Path repoPath) {
        Path yaml = repoPath.resolve("ops/atenea-runtime.yml");
        if (Files.isRegularFile(yaml)) {
            return Optional.of(yaml);
        }
        Path yml = repoPath.resolve("ops/atenea-runtime.yaml");
        if (Files.isRegularFile(yml)) {
            return Optional.of(yml);
        }
        Path json = repoPath.resolve("ops/atenea-runtime.json");
        if (Files.isRegularFile(json)) {
            return Optional.of(json);
        }
        return Optional.empty();
    }

    private ProjectRuntimeContract readContract(Path contractPath, Path repoPath) {
        try {
            Map<String, Object> values = contractPath.toString().endsWith(".json")
                    ? readJsonContract(contractPath)
                    : readSimpleYamlContract(contractPath);
            String baseUrl = text(values, "browserTestBaseUrl");
            if (baseUrl == null) {
                baseUrl = text(values, "hostBaseUrl");
            }
            String healthPath = firstNonBlank(text(values, "healthPath"), "/");
            Duration commandTimeout = durationSeconds(values, "commandTimeoutSeconds", COMMAND_TIMEOUT);
            Duration healthStartupTimeout = durationSeconds(values, "healthStartupTimeoutSeconds", HEALTH_STARTUP_TIMEOUT);
            return new ProjectRuntimeContract(
                    repoPath,
                    resolveCommandRepoPath(repoPath),
                    contractPath,
                    firstNonBlank(text(values, "runtimeProfile"), "default"),
                    baseUrl,
                    joinUrl(baseUrl, healthPath),
                    text(values, "healthCommand"),
                    text(values, "startCommand"),
                    text(values, "cleanupCommand"),
                    booleanValue(values, "cleanupAfterVerification", false),
                    text(values, "testCommand"),
                    text(values, "browserTestCommand"),
                    text(values, "browserTestWorkingDirectory"),
                    list(values, "artifactPaths"),
                    commandTimeout,
                    healthStartupTimeout);
        } catch (IOException ex) {
            throw new CoreInvalidContextException("Cannot read project runtime contract: " + ex.getMessage());
        }
    }

    private Map<String, Object> readJsonContract(Path contractPath) throws IOException {
        return objectMapper.readValue(contractPath.toFile(), new TypeReference<>() {
        });
    }

    private Map<String, Object> readSimpleYamlContract(Path contractPath) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        String activeListKey = null;
        for (String rawLine : Files.readAllLines(contractPath, StandardCharsets.UTF_8)) {
            String line = rawLine.stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ") && activeListKey != null) {
                @SuppressWarnings("unchecked")
                List<String> items = (List<String>) values.computeIfAbsent(activeListKey, ignored -> new ArrayList<String>());
                items.add(unquote(trimmed.substring(2).trim()));
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (value.isBlank()) {
                activeListKey = key;
                values.putIfAbsent(key, new ArrayList<String>());
            } else {
                activeListKey = null;
                values.put(key, unquote(value));
            }
        }
        return values;
    }

    private HealthProbeResult probeHealth(ProjectRuntimeContract contract) {
        if (contract.healthCommand() != null) {
            CommandResult result = runControlledCommand(
                    "runtime-health",
                    contract,
                    contract.healthCommand(),
                    Duration.ofSeconds(30));
            boolean ok = result.exitCode() == 0;
            return new HealthProbeResult(
                    ok,
                    result.exitCode(),
                    result.durationMillis(),
                    ok ? "Health command correcto." : result.summary(),
                    contract.baseUrl(),
                    contract.healthUrl());
        }
        String healthUrl = contract.healthUrl();
        if (healthUrl == null || healthUrl.isBlank()) {
            return new HealthProbeResult(false, null, 0L, "El contrato no define una URL de health check.", null, null);
        }
        HealthProbeResult primary = probeHealthUrl(healthUrl, contract.baseUrl());
        if (primary.ok() || !isLocalhostUrl(contract.baseUrl())) {
            return primary;
        }
        for (String fallbackBaseUrl : dockerHostFallbackUrls(contract.baseUrl())) {
            HealthProbeResult fallback = probeHealthUrl(
                    joinUrl(fallbackBaseUrl, healthPathFrom(contract.baseUrl(), contract.healthUrl())),
                    fallbackBaseUrl);
            if (fallback.ok()) {
                return fallback;
            }
        }
        return primary;
    }

    private HealthProbeResult probeHealthUrl(String healthUrl, String effectiveBaseUrl) {
        Instant started = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long durationMillis = Duration.between(started, Instant.now()).toMillis();
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 400;
            return new HealthProbeResult(
                    ok,
                    response.statusCode(),
                    durationMillis,
                    ok
                            ? "Health check correcto con estado " + response.statusCode() + "."
                            : "Health check respondió con estado " + response.statusCode() + ".",
                    effectiveBaseUrl,
                    healthUrl);
        } catch (Exception ex) {
            long durationMillis = Duration.between(started, Instant.now()).toMillis();
            return new HealthProbeResult(false, null, durationMillis, sanitize(ex.getMessage()), null, null);
        }
    }

    private HealthProbeResult waitForHealth(ProjectRuntimeContract contract) {
        Instant started = Instant.now();
        HealthProbeResult lastResult = probeHealth(contract);
        while (!lastResult.ok()
                && Duration.between(started, Instant.now()).compareTo(contract.healthStartupTimeout()) < 0) {
            sleep(HEALTH_POLL_INTERVAL);
            lastResult = probeHealth(contract);
        }
        long durationMillis = Duration.between(started, Instant.now()).toMillis();
        if (lastResult.ok()) {
            return new HealthProbeResult(
                    true,
                    lastResult.statusCode(),
                    durationMillis,
                    "Runtime disponible tras arranque.",
                    lastResult.effectiveBaseUrl(),
                    lastResult.effectiveHealthUrl());
        }
        return new HealthProbeResult(
                false,
                lastResult.statusCode(),
                durationMillis,
                "Runtime no quedó disponible tras "
                        + contract.healthStartupTimeout().toSeconds()
                        + " segundos. Último resultado: "
                        + lastResult.summary(),
                lastResult.effectiveBaseUrl(),
                lastResult.effectiveHealthUrl());
    }

    private boolean isLocalhostUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(url).getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private List<String> dockerHostFallbackUrls(String baseUrl) {
        URI uri = URI.create(baseUrl);
        int port = uri.getPort();
        String portSuffix = port > 0 ? ":" + port : "";
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        return List.of(
                scheme + "://host.docker.internal" + portSuffix,
                scheme + "://172.17.0.1" + portSuffix);
    }

    private String healthPathFrom(String baseUrl, String healthUrl) {
        try {
            URI base = URI.create(baseUrl);
            URI health = URI.create(healthUrl);
            String path = health.getPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException ex) {
            return "/";
        }
    }

    private CommandResult runBrowserCommand(ProjectRuntimeContract contract) {
        return runControlledCommand(
                "browser-smoke",
                contract,
                contract.browserTestCommand(),
                contract.commandTimeout());
    }

    private CommandResult runControlledCommand(
            String stepName,
            ProjectRuntimeContract contract,
            String command,
            Duration timeout
    ) {
        validateControlledCommand(stepName, command);
        Path workingDirectory = contract.repoPath();
        if (contract.commandRepoPath() != null && Files.isDirectory(contract.commandRepoPath())) {
            workingDirectory = contract.commandRepoPath();
        }
        if ("browser-smoke".equals(stepName) && contract.browserTestWorkingDirectory() != null) {
            workingDirectory = workingDirectory.resolve(contract.browserTestWorkingDirectory()).normalize();
        }
        Instant started = Instant.now();
        ProcessBuilder processBuilder = new ProcessBuilder("bash", "-lc", command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().putIfAbsent("HOME", "/tmp");
        processBuilder.environment().putIfAbsent("DOCKER_CONFIG", "/tmp/.docker");
        if (contract.baseUrl() != null) {
            processBuilder.environment().put("PLAYWRIGHT_BASE_URL", contract.baseUrl());
            processBuilder.environment().put("BASE_URL", contract.baseUrl());
            processBuilder.environment().put("ATENEA_RUNTIME_BASE_URL", contract.baseUrl());
        }
        if (contract.commandRepoPath() != null) {
            processBuilder.environment().put("ATENEA_RUNTIME_REPO_PATH", contract.commandRepoPath().toString());
        }
        try {
            Process process = processBuilder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean completed = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                String output = outputFrom(outputFuture);
                return new CommandResult(
                        124,
                        Duration.between(started, Instant.now()).toMillis(),
                        "El comando " + stepName + " superó el tiempo máximo de "
                                + timeout.toSeconds() + " segundos. " + summarizeOutput(output));
            }
            String output = outputFrom(outputFuture);
            return new CommandResult(
                    process.exitValue(),
                    Duration.between(started, Instant.now()).toMillis(),
                    summarizeOutput(output));
        } catch (IOException ex) {
            return new CommandResult(
                    127,
                    Duration.between(started, Instant.now()).toMillis(),
                    sanitize(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CommandResult(
                    130,
                    Duration.between(started, Instant.now()).toMillis(),
                    "La ejecución fue interrumpida.");
        }
    }

    private String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return sanitize(ex.getMessage());
        }
    }

    private String outputFrom(CompletableFuture<String> outputFuture) {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "La lectura de salida fue interrumpida.";
        } catch (ExecutionException ex) {
            return sanitize(ex.getMessage());
        } catch (java.util.concurrent.TimeoutException ex) {
            return "La salida del comando no terminó de cerrarse a tiempo.";
        }
    }

    private void validateControlledCommand(String stepName, String command) {
        if (command == null || command.isBlank()) {
            throw new CoreInvalidContextException("Missing runtime command for verification step: " + stepName);
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        boolean allowed = normalized.startsWith("docker compose ")
                || normalized.startsWith("docker-compose ")
                || normalized.startsWith("docker run ")
                || normalized.startsWith("docker exec ")
                || normalized.startsWith("npm ")
                || normalized.startsWith("npx ")
                || normalized.startsWith("mvn ")
                || normalized.startsWith("./mvnw ")
                || normalized.startsWith("gradle ")
                || normalized.startsWith("./gradlew ")
                || normalized.startsWith("bash scripts/")
                || normalized.startsWith("./scripts/")
                || normalized.startsWith("curl ");
        if (!allowed) {
            throw new CoreInvalidContextException("Runtime command is not allowed for verification step: " + stepName);
        }
        List<String> denied = List.of("rm -rf /", " mkfs", " dd if=", " shutdown", " reboot", ":(){");
        for (String token : denied) {
            if (normalized.contains(token)) {
                throw new CoreInvalidContextException("Runtime command contains a denied operation: " + stepName);
            }
        }
    }

    private String runtimeUnavailableAction(ProjectRuntimeContract contract) {
        if (contract.contractPath() == null) {
            return "Añadir contrato operativo del proyecto.";
        }
        return "Arrancar el runtime declarado por el contrato y repetir la verificación antes de desplegar.";
    }

    private ProjectVerificationResponse toResponse(
            ProjectVerificationRunEntity entity,
            List<ProjectVerificationTestResult> tests,
            List<String> artifacts
    ) {
        return new ProjectVerificationResponse(
                entity.getId(),
                entity.getProject().getId(),
                entity.getWorkSession() == null ? null : entity.getWorkSession().getId(),
                entity.getStatus(),
                entity.getRuntimeContractPath(),
                entity.getRuntimeProfile(),
                entity.getBaseUrl(),
                entity.getDecisionBrief(),
                entity.getTechnicalSummary(),
                entity.getBlockerType(),
                entity.getBlockerSummary(),
                entity.getRecommendedAction(),
                List.copyOf(tests),
                List.copyOf(artifacts),
                entity.getStartedAt(),
                entity.getFinishedAt());
    }

    private ProjectVerificationResponse persist(
            ProjectEntity project,
            WorkSessionEntity workSession,
            ProjectVerificationStatus status,
            ProjectRuntimeContract completedContract,
            String decisionBrief,
            String technicalSummary,
            String blockerType,
            String blockerSummary,
            String recommendedAction,
            List<ProjectVerificationTestResult> tests,
            List<String> artifacts,
            Instant startedAt
    ) {
        Instant finishedAt = Instant.now();
        ProjectVerificationRunEntity entity = new ProjectVerificationRunEntity();
        entity.setProject(project);
        entity.setWorkSession(workSession);
        entity.setStatus(status);
        entity.setRuntimeContractPath(completedContract.contractPath());
        entity.setRuntimeProfile(completedContract.runtimeProfile());
        entity.setBaseUrl(completedContract.baseUrl());
        entity.setDecisionBrief(decisionBrief);
        entity.setTechnicalSummary(technicalSummary);
        entity.setBlockerType(blockerType);
        entity.setBlockerSummary(blockerSummary);
        entity.setRecommendedAction(recommendedAction);
        entity.setTestsJson(writeJson(tests));
        entity.setArtifactsJson(writeJson(artifacts));
        entity.setStartedAt(startedAt);
        entity.setFinishedAt(finishedAt);
        entity.setCreatedAt(finishedAt);
        return toResponse(verificationRunRepository.save(entity), tests, artifacts);
    }

    private Path resolveRepoPath(String repoPath) {
        Path direct = Path.of(repoPath);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        if (repoPath.startsWith("/workspace/")) {
            Path hostPath = Path.of("/srv/atenea/workspace").resolve(repoPath.substring("/workspace/".length()));
            if (Files.isDirectory(hostPath)) {
                return hostPath;
            }
        }
        return direct;
    }

    private Path resolveCommandRepoPath(Path repoPath) {
        String raw = repoPath.toString();
        if (raw.startsWith("/workspace/")) {
            Path hostPath = Path.of("/srv/atenea/workspace").resolve(raw.substring("/workspace/".length()));
            if (Files.isDirectory(hostPath)) {
                return hostPath;
            }
        }
        if (raw.startsWith("/srv/atenea/") && Files.isDirectory(repoPath)) {
            return repoPath;
        }
        return repoPath;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<String> list(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = text(values, key);
        if (text == null) {
            return List.of();
        }
        return List.of(text);
    }

    private boolean booleanValue(Map<String, Object> values, String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text)
                || "1".equals(text);
    }

    private Duration durationSeconds(Map<String, Object> values, String key, Duration defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number && number.longValue() > 0) {
            return Duration.ofSeconds(number.longValue());
        }
        try {
            long seconds = Long.parseLong(String.valueOf(value).trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        if (path == null || path.isBlank() || "/".equals(path)) {
            return baseUrl;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String unquote(String value) {
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String summarizeOutput(String output) {
        String sanitized = sanitize(output);
        if (sanitized.length() <= OUTPUT_SUMMARY_LIMIT) {
            return sanitized;
        }
        return sanitized.substring(0, OUTPUT_SUMMARY_LIMIT - 3).trim() + "...";
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "Sin salida disponible.";
        }
        return value
                .replaceAll("[\\p{Cntrl}&&[^\n\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CoreInvalidContextException("Project verification was interrupted");
        }
    }

    private record ProjectRuntimeContract(
            Path repoPath,
            Path commandRepoPath,
            Path contractPathValue,
            String runtimeProfile,
            String baseUrl,
            String healthUrl,
            String healthCommand,
            String startCommand,
            String cleanupCommand,
            boolean cleanupAfterVerification,
            String testCommand,
            String browserTestCommand,
            String browserTestWorkingDirectory,
            List<String> artifactPaths,
            Duration commandTimeout,
            Duration healthStartupTimeout
    ) {
        static ProjectRuntimeContract missing(Path repoPath) {
            return new ProjectRuntimeContract(
                    repoPath,
                    repoPath,
                    null,
                    "default",
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    List.of(),
                    COMMAND_TIMEOUT,
                    HEALTH_STARTUP_TIMEOUT);
        }

        String contractPath() {
            return contractPathValue == null ? null : contractPathValue.toString();
        }

        boolean testOnly() {
            return testCommand != null
                    && healthCommand == null
                    && healthUrl == null
                    && startCommand == null
                    && browserTestCommand == null;
        }

        ProjectRuntimeContract withBaseUrl(String effectiveBaseUrl, String effectiveHealthUrl) {
            return new ProjectRuntimeContract(
                    repoPath,
                    commandRepoPath,
                    contractPathValue,
                    runtimeProfile,
                    effectiveBaseUrl,
                    effectiveHealthUrl,
                    healthCommand,
                    startCommand,
                    cleanupCommand,
                    cleanupAfterVerification,
                    testCommand,
                    browserTestCommand,
                    browserTestWorkingDirectory,
                    artifactPaths,
                    commandTimeout,
                    healthStartupTimeout);
        }
    }

    private record HealthProbeResult(
            boolean ok,
            Integer statusCode,
            long durationMillis,
            String summary,
            String effectiveBaseUrl,
            String effectiveHealthUrl
    ) {
    }

    private record CommandResult(
            int exitCode,
            long durationMillis,
            String summary
    ) {
    }
}
