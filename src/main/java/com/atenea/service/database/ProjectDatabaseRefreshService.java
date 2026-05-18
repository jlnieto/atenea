package com.atenea.service.database;

import com.atenea.persistence.database.ProjectDatabaseRefreshRunEntity;
import com.atenea.persistence.database.ProjectDatabaseRefreshRunRepository;
import com.atenea.persistence.database.ProjectDatabaseRefreshStatus;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.service.core.CoreInvalidContextException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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

@Service
public class ProjectDatabaseRefreshService {

    private static final Duration DEFAULT_REFRESH_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(8);
    private static final int OUTPUT_SUMMARY_LIMIT = 3000;

    private final ProjectRepository projectRepository;
    private final ProjectDatabaseRefreshRunRepository refreshRunRepository;
    private final ObjectMapper objectMapper;

    public ProjectDatabaseRefreshService(
            ProjectRepository projectRepository,
            ProjectDatabaseRefreshRunRepository refreshRunRepository,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.refreshRunRepository = refreshRunRepository;
        this.objectMapper = objectMapper;
    }

    public ProjectDatabaseRefreshResponse runRefresh(Long projectId) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: projectId"));

        Instant startedAt = Instant.now();
        ProjectDatabaseRefreshRunEntity run = new ProjectDatabaseRefreshRunEntity();
        run.setProject(project);
        run.setStatus(ProjectDatabaseRefreshStatus.RUNNING);
        run.setDecisionBrief("Actualización de base de datos local iniciada.");
        run.setStartedAt(startedAt);
        run.setCreatedAt(startedAt);
        run = refreshRunRepository.save(run);

        DatabaseRefreshContract contract = DatabaseRefreshContract.missing(resolveRepoPath(project.getRepoPath()));
        ProjectDatabaseRefreshStatus status;
        String decisionBrief;
        String technicalSummary;
        String blockerType = null;
        String blockerSummary = null;
        String recommendedAction = null;
        Integer commandExitCode = null;
        String commandOutputSummary = null;
        Long durationMillis = null;

        try {
            Optional<Path> contractPath = findContract(contract.repoPath());
            if (contractPath.isEmpty()) {
                status = ProjectDatabaseRefreshStatus.BLOCKED;
                decisionBrief = "No puedo actualizar la BD local: falta el contrato operativo del proyecto.";
                technicalSummary = "El repositorio no declara ops/atenea-runtime.yml ni ops/atenea-runtime.json.";
                blockerType = "MISSING_RUNTIME_CONTRACT";
                blockerSummary = technicalSummary;
                recommendedAction = "Añadir un contrato operativo con dataRefreshEnabled, databaseReplaceMode y dataRefreshCommand.";
            } else {
                contract = readContract(contractPath.get(), contract.repoPath());
                contract.validateRefreshPolicy();

                CommandResult startResult = null;
                if (contract.databaseStartCommand() != null) {
                    startResult = runControlledCommand(
                            "database-start",
                            contract,
                            contract.databaseStartCommand(),
                            DEFAULT_START_TIMEOUT);
                    if (startResult.exitCode() != 0) {
                        status = ProjectDatabaseRefreshStatus.FAILED;
                        decisionBrief = "No se pudo preparar la base de datos local antes del reemplazo.";
                        technicalSummary = "El comando de arranque de BD falló con salida " + startResult.exitCode() + ".";
                        commandExitCode = startResult.exitCode();
                        commandOutputSummary = startResult.summary();
                        durationMillis = startResult.durationMillis();
                        return finish(run, contract, status, decisionBrief, technicalSummary, blockerType,
                                blockerSummary, recommendedAction, commandExitCode, commandOutputSummary,
                                durationMillis, Instant.now());
                    }
                }

                CommandResult refreshResult = runControlledCommand(
                        "database-refresh",
                        contract,
                        contract.dataRefreshCommand(),
                        contract.dataRefreshTimeout());
                status = refreshResult.exitCode() == 0
                        ? ProjectDatabaseRefreshStatus.PASSED
                        : ProjectDatabaseRefreshStatus.FAILED;
                decisionBrief = refreshResult.exitCode() == 0
                        ? "Base de datos local actualizada correctamente desde producción."
                        : "No se pudo reemplazar la base de datos local.";
                technicalSummary = buildTechnicalSummary(contract, startResult, refreshResult);
                commandExitCode = refreshResult.exitCode();
                commandOutputSummary = refreshResult.summary();
                durationMillis = refreshResult.durationMillis();
                if (refreshResult.exitCode() != 0) {
                    blockerType = "REFRESH_COMMAND_FAILED";
                    blockerSummary = refreshResult.summary();
                    recommendedAction = "Revisar la salida del script canónico del proyecto y repetir solo cuando esté corregido.";
                }
            }
        } catch (CoreInvalidContextException exception) {
            status = ProjectDatabaseRefreshStatus.BLOCKED;
            decisionBrief = "No puedo actualizar la BD local: el contrato no es seguro o está incompleto.";
            technicalSummary = exception.getMessage();
            blockerType = "INVALID_REFRESH_CONTRACT";
            blockerSummary = exception.getMessage();
            recommendedAction = "Corregir el contrato operativo del proyecto antes de ejecutar un reemplazo de datos.";
        } catch (RuntimeException exception) {
            status = ProjectDatabaseRefreshStatus.FAILED;
            decisionBrief = "No se pudo reemplazar la base de datos local.";
            technicalSummary = sanitize(exception.getMessage());
            blockerType = "UNEXPECTED_REFRESH_FAILURE";
            blockerSummary = sanitize(exception.getMessage());
            recommendedAction = "Revisar el error técnico y repetir la operación explícitamente cuando esté corregido.";
        }

        return finish(run, contract, status, decisionBrief, technicalSummary, blockerType,
                blockerSummary, recommendedAction, commandExitCode, commandOutputSummary,
                durationMillis, Instant.now());
    }

    private ProjectDatabaseRefreshResponse finish(
            ProjectDatabaseRefreshRunEntity run,
            DatabaseRefreshContract contract,
            ProjectDatabaseRefreshStatus status,
            String decisionBrief,
            String technicalSummary,
            String blockerType,
            String blockerSummary,
            String recommendedAction,
            Integer commandExitCode,
            String commandOutputSummary,
            Long durationMillis,
            Instant finishedAt
    ) {
        run.setStatus(status);
        run.setRuntimeContractPath(contract.contractPath());
        run.setDatabaseEngine(contract.databaseEngine());
        run.setLocalDatabase(contract.databaseLocalName());
        run.setSourceHost(contract.sourceLabel());
        run.setSourceDatabase(contract.dataRefreshSourceDatabase());
        run.setDecisionBrief(decisionBrief);
        run.setTechnicalSummary(summarizeOutput(technicalSummary));
        run.setBlockerType(blockerType);
        run.setBlockerSummary(summarizeOutput(blockerSummary));
        run.setRecommendedAction(recommendedAction);
        run.setCommandExitCode(commandExitCode);
        run.setCommandOutputSummary(commandOutputSummary);
        run.setDurationMillis(durationMillis == null
                ? Duration.between(run.getStartedAt(), finishedAt).toMillis()
                : durationMillis);
        run.setFinishedAt(finishedAt);
        ProjectDatabaseRefreshRunEntity saved = refreshRunRepository.save(run);
        return toResponse(saved);
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

    private DatabaseRefreshContract readContract(Path contractPath, Path repoPath) {
        try {
            Map<String, Object> values = contractPath.toString().endsWith(".json")
                    ? readJsonContract(contractPath)
                    : readSimpleYamlContract(contractPath);
            return new DatabaseRefreshContract(
                    repoPath,
                    resolveCommandRepoPath(repoPath),
                    contractPath,
                    text(values, "databaseEngine"),
                    text(values, "databaseLocalName"),
                    firstNonBlank(text(values, "databaseReplaceMode"), "explicit_only"),
                    booleanValue(values, "dataRefreshEnabled", false),
                    text(values, "dataRefreshSourceHost"),
                    text(values, "dataRefreshSourceAddress"),
                    text(values, "dataRefreshSourceDatabase"),
                    text(values, "databaseStartCommand"),
                    text(values, "dataRefreshCommand"),
                    durationSeconds(values, "dataRefreshTimeoutSeconds", DEFAULT_REFRESH_TIMEOUT));
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

    private CommandResult runControlledCommand(
            String stepName,
            DatabaseRefreshContract contract,
            String command,
            Duration timeout
    ) {
        validateControlledCommand(stepName, command);
        Path workingDirectory = contract.commandRepoPath() != null && Files.isDirectory(contract.commandRepoPath())
                ? contract.commandRepoPath()
                : contract.repoPath();
        Instant started = Instant.now();
        ProcessBuilder processBuilder = new ProcessBuilder("bash", "-lc", command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().putIfAbsent("HOME", "/tmp");
        processBuilder.environment().putIfAbsent("DOCKER_CONFIG", "/tmp/.docker");
        processBuilder.environment().put("ATENEA_RUNTIME_REPO_PATH", workingDirectory.toString());
        if (contract.dataRefreshSourceAddress() != null) {
            processBuilder.environment().put("SOURCE_HOST", contract.dataRefreshSourceAddress());
        }
        if (contract.dataRefreshSourceDatabase() != null) {
            processBuilder.environment().put("SOURCE_DB", contract.dataRefreshSourceDatabase());
        }
        if (contract.databaseLocalName() != null) {
            processBuilder.environment().put("TARGET_DB", contract.databaseLocalName());
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

    private void validateControlledCommand(String stepName, String command) {
        if (command == null || command.isBlank()) {
            throw new CoreInvalidContextException("Missing runtime command for database step: " + stepName);
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        boolean allowed;
        if ("database-start".equals(stepName)) {
            allowed = normalized.startsWith("docker compose ")
                    || normalized.startsWith("docker-compose ");
        } else {
            allowed = normalized.equals("bash scripts/dev/replace-local-db-from-prod.sh --force")
                    || normalized.equals("./scripts/dev/replace-local-db-from-prod.sh --force")
                    || normalized.equals("bash ops/import-historic-db.sh")
                    || normalized.equals("./ops/import-historic-db.sh");
        }
        if (!allowed) {
            throw new CoreInvalidContextException("Database command is not allowlisted for step: " + stepName);
        }
        List<String> denied = List.of("rm -rf /", " mkfs", " dd if=", " shutdown", " reboot", ":(){");
        for (String token : denied) {
            if (normalized.contains(token)) {
                throw new CoreInvalidContextException("Database command contains a denied operation: " + stepName);
            }
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

    private String buildTechnicalSummary(
            DatabaseRefreshContract contract,
            CommandResult startResult,
            CommandResult refreshResult
    ) {
        StringBuilder summary = new StringBuilder()
                .append("Proyecto con motor ")
                .append(firstNonBlank(contract.databaseEngine(), "desconocido"))
                .append(", BD local ")
                .append(firstNonBlank(contract.databaseLocalName(), "no declarada"))
                .append(", origen ")
                .append(firstNonBlank(contract.sourceLabel(), "no declarado"))
                .append(".");
        if (startResult != null) {
            summary.append(" Arranque BD exitCode=").append(startResult.exitCode()).append(".");
        }
        summary.append(" Reemplazo exitCode=").append(refreshResult.exitCode()).append(".");
        return summary.toString();
    }

    private ProjectDatabaseRefreshResponse toResponse(ProjectDatabaseRefreshRunEntity entity) {
        return new ProjectDatabaseRefreshResponse(
                entity.getId(),
                entity.getProject().getId(),
                entity.getStatus(),
                entity.getRuntimeContractPath(),
                entity.getDatabaseEngine(),
                entity.getLocalDatabase(),
                entity.getSourceHost(),
                entity.getSourceDatabase(),
                entity.getDecisionBrief(),
                entity.getTechnicalSummary(),
                entity.getBlockerType(),
                entity.getBlockerSummary(),
                entity.getRecommendedAction(),
                entity.getCommandExitCode(),
                entity.getCommandOutputSummary(),
                entity.getDurationMillis(),
                entity.getStartedAt(),
                entity.getFinishedAt());
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
        if (output == null) {
            return null;
        }
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

    private record DatabaseRefreshContract(
            Path repoPath,
            Path commandRepoPath,
            Path contractPathValue,
            String databaseEngine,
            String databaseLocalName,
            String databaseReplaceMode,
            boolean dataRefreshEnabled,
            String dataRefreshSourceHost,
            String dataRefreshSourceAddress,
            String dataRefreshSourceDatabase,
            String databaseStartCommand,
            String dataRefreshCommand,
            Duration dataRefreshTimeout
    ) {
        static DatabaseRefreshContract missing(Path repoPath) {
            return new DatabaseRefreshContract(
                    repoPath,
                    repoPath,
                    null,
                    null,
                    null,
                    "explicit_only",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    DEFAULT_REFRESH_TIMEOUT);
        }

        String contractPath() {
            return contractPathValue == null ? null : contractPathValue.toString();
        }

        String sourceLabel() {
            if (dataRefreshSourceHost == null || dataRefreshSourceHost.isBlank()) {
                return dataRefreshSourceAddress;
            }
            if (dataRefreshSourceAddress == null || dataRefreshSourceAddress.isBlank()) {
                return dataRefreshSourceHost;
            }
            return dataRefreshSourceHost + " (" + dataRefreshSourceAddress + ")";
        }

        void validateRefreshPolicy() {
            if (!dataRefreshEnabled) {
                throw new CoreInvalidContextException("El contrato no habilita dataRefreshEnabled.");
            }
            if (!"explicit_only".equalsIgnoreCase(databaseReplaceMode)) {
                throw new CoreInvalidContextException("databaseReplaceMode debe ser explicit_only.");
            }
            if (databaseEngine == null || databaseEngine.isBlank()) {
                throw new CoreInvalidContextException("El contrato no declara databaseEngine.");
            }
            String normalizedEngine = databaseEngine.toLowerCase(Locale.ROOT);
            if (!"mariadb".equals(normalizedEngine) && !"postgres".equals(normalizedEngine)
                    && !"postgresql".equals(normalizedEngine)) {
                throw new CoreInvalidContextException("databaseEngine no soportado: " + databaseEngine);
            }
            if (databaseLocalName == null || databaseLocalName.isBlank()) {
                throw new CoreInvalidContextException("El contrato no declara databaseLocalName.");
            }
            if (dataRefreshSourceHost == null && dataRefreshSourceAddress == null) {
                throw new CoreInvalidContextException("El contrato no declara dataRefreshSourceHost o dataRefreshSourceAddress.");
            }
            if (dataRefreshSourceDatabase == null || dataRefreshSourceDatabase.isBlank()) {
                throw new CoreInvalidContextException("El contrato no declara dataRefreshSourceDatabase.");
            }
            if (dataRefreshCommand == null || dataRefreshCommand.isBlank()) {
                throw new CoreInvalidContextException("El contrato no declara dataRefreshCommand.");
            }
        }
    }

    private record CommandResult(
            int exitCode,
            long durationMillis,
            String summary
    ) {
    }
}
