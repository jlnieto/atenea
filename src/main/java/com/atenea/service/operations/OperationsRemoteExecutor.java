package com.atenea.service.operations;

import com.atenea.persistence.operations.ManagedHostEntity;
import java.time.Duration;

public interface OperationsRemoteExecutor {

    RemoteCommandResult execute(ManagedHostEntity host, String command, Duration timeout);
}
