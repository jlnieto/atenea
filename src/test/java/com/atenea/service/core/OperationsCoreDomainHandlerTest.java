package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.api.operations.ManagedServiceResponse;
import com.atenea.api.operations.OperationsActionRunResponse;
import com.atenea.api.operations.OperationsExecutionReportResponse;
import com.atenea.api.operations.OperationsExecutionStepResponse;
import com.atenea.api.operations.OperationsIncidentResponse;
import com.atenea.api.operations.OperationsRecoveryResponse;
import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.operations.ManagedServiceType;
import com.atenea.persistence.operations.OperationsActionRunStatus;
import com.atenea.persistence.operations.OperationsIncidentStatus;
import com.atenea.persistence.operations.OperationsSeverity;
import com.atenea.service.operations.OperationsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationsCoreDomainHandlerTest {

    @Mock
    private OperationsService operationsService;

    private OperationsCoreDomainHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OperationsCoreDomainHandler(operationsService);
    }

    @Test
    void recoverApacheDelegatesToOperationsService() {
        OperationsRecoveryResponse recovery = new OperationsRecoveryResponse(
                new ManagedHostResponse(3L, "dedicado-principal", "Dedicado", "prod", true),
                new ManagedServiceResponse(9L, 3L, "apache", ManagedServiceType.WEB_SERVER, "apache2", "apache2", true),
                new OperationsActionRunResponse(
                        44L,
                        20L,
                        3L,
                        9L,
                        "APACHE_RECOVERY",
                        OperationsActionRunStatus.SUCCEEDED,
                        0,
                        "{\"status\":\"OK\"}",
                        null,
                        new OperationsExecutionReportResponse(
                                "APACHE_RECOVERY",
                                "dedicado-principal",
                                "OK",
                                "Apache reiniciado, 2 procesos colgados eliminados y validación local correcta.",
                                List.of(
                                        new OperationsExecutionStepResponse(
                                                "snapshot_before",
                                                "OK",
                                                "Apache activo antes de intervenir; 24 procesos detectados."),
                                        new OperationsExecutionStepResponse(
                                                "kill_leftover_processes",
                                                "OK",
                                                "2 procesos restantes terminados tras parar apache2."),
                                        new OperationsExecutionStepResponse(
                                                "verify_service",
                                                "OK",
                                                "systemctl is-active apache2 = active; apachectl configtest = Syntax OK.")),
                                new LinkedHashMap<>(Map.of(
                                        "apacheProcessesBefore", 24,
                                        "apacheProcessesAfter", 10,
                                        "leftoverKilled", 2))),
                        Instant.parse("2026-05-13T10:00:00Z"),
                        Instant.parse("2026-05-13T10:00:05Z")),
                new OperationsIncidentResponse(
                        20L,
                        3L,
                        "dedicado-principal",
                        9L,
                        "apache",
                        OperationsIncidentStatus.RESOLVED,
                        OperationsSeverity.INFO,
                        "Recuperación controlada de Apache",
                        "OK",
                        Instant.parse("2026-05-13T09:59:00Z"),
                        Instant.parse("2026-05-13T10:00:05Z"),
                        Instant.parse("2026-05-13T10:00:05Z")),
                List.of(new WebsiteCheckResponse(
                        1L,
                        "Cliente",
                        "https://cliente.test",
                        200,
                        200,
                        100,
                        2500,
                        10000,
                        "OK",
                        true,
                        null)));
        when(operationsService.recoverApacheHungProcesses(3L)).thenReturn(recovery);

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "RECOVER_APACHE_HUNG_PROCESSES",
                        CoreDomain.OPERATIONS,
                        "recover_apache_hung_processes",
                        Map.of("hostId", 3L),
                        BigDecimal.valueOf(0.96),
                        CoreRiskLevel.DESTRUCTIVE,
                        true),
                new CoreExecutionContext(101L, null, null, "default", true, "token"));

        assertEquals(CoreResultType.OPERATIONS_ACTION_RUN, result.resultType());
        assertEquals(CoreTargetType.OPERATIONS_ACTION_RUN, result.targetType());
        assertEquals(44L, result.targetId());
        String message = result.operatorMessage();
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("Qué he hecho:"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("2 procesos restantes terminados"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("Checks web externos desde Atenea: 1/1 correctos"));
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("Resultado: He ejecutado la recuperación controlada de Apache"));
    }
}
