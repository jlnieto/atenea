package com.atenea.service.operations;

import com.atenea.persistence.operations.ManagedHostEntity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class SshOperationsRemoteExecutor implements OperationsRemoteExecutor {

    @Override
    public RemoteCommandResult execute(ManagedHostEntity host, String command, Duration timeout) {
        List<String> args = new ArrayList<>();
        args.add("ssh");
        args.add("-o");
        args.add("BatchMode=yes");
        args.add("-o");
        args.add("StrictHostKeyChecking=accept-new");
        args.add("-o");
        args.add("UserKnownHostsFile=/tmp/atenea_known_hosts");
        args.add("-o");
        args.add("LogLevel=ERROR");
        args.add("-o");
        args.add("ConnectionAttempts=2");
        args.add("-o");
        args.add("ConnectTimeout=10");
        args.add("-p");
        args.add(String.valueOf(host.getSshPort()));
        if (host.getSshKeyPath() != null && !host.getSshKeyPath().isBlank()) {
            args.add("-i");
            args.add(host.getSshKeyPath());
        }
        args.add(host.getSshUser() + "@" + host.getSshHost());
        args.add(command);

        Process process = null;
        try {
            process = new ProcessBuilder(args).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new OperationsRemoteExecutionException("Remote command timed out after " + timeout);
            }
            String stdoutText = stdout.join();
            String stderrText = stderr.join();
            if (process.exitValue() == 255 && stdoutText.isBlank() && stderrText.isBlank()) {
                stderrText = "SSH terminó con código 255 sin devolver detalle. No se pudo abrir la sesión remota en esta lectura.";
            }
            return new RemoteCommandResult(process.exitValue(), stdoutText, stderrText);
        } catch (OperationsRemoteExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OperationsRemoteExecutionException("Remote command execution failed", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private CompletableFuture<String> readAsync(java.io.InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new OperationsRemoteExecutionException("Remote command output could not be read", exception);
            }
        });
    }
}
