package com.atenea.service.operations;

public record RemoteCommandResult(
        int exitCode,
        String stdout,
        String stderr
) {
}
