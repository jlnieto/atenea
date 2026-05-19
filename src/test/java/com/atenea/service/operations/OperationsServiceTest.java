package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.atenea.api.operations.OperationsRecoveryResponse;
import com.atenea.api.operations.OperationsHostStatusResponse;
import com.atenea.api.operations.OperationsServiceCheckResponse;
import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.persistence.operations.ManagedHostEntity;
import com.atenea.persistence.operations.ManagedHostRepository;
import com.atenea.persistence.operations.ManagedServiceEntity;
import com.atenea.persistence.operations.ManagedServiceRepository;
import com.atenea.persistence.operations.ManagedServiceType;
import com.atenea.persistence.operations.ManagedWebsiteEntity;
import com.atenea.persistence.operations.ManagedWebsiteRepository;
import com.atenea.persistence.operations.OperationsActionRunEntity;
import com.atenea.persistence.operations.OperationsActionRunRepository;
import com.atenea.persistence.operations.OperationsIncidentEntity;
import com.atenea.persistence.operations.OperationsIncidentRepository;
import com.atenea.persistence.operations.OperationsIncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationsServiceTest {

    @Mock
    private ManagedHostRepository managedHostRepository;
    @Mock
    private ManagedServiceRepository managedServiceRepository;
    @Mock
    private ManagedWebsiteRepository managedWebsiteRepository;
    @Mock
    private OperationsIncidentRepository operationsIncidentRepository;
    @Mock
    private OperationsActionRunRepository operationsActionRunRepository;
    @Mock
    private OperationsRemoteExecutor operationsRemoteExecutor;
    @Mock
    private OperationsWebsiteCheckService operationsWebsiteCheckService;

    private OperationsService operationsService;

    @BeforeEach
    void setUp() {
        operationsService = new OperationsService(
                managedHostRepository,
                managedServiceRepository,
                managedWebsiteRepository,
                operationsIncidentRepository,
                operationsActionRunRepository,
                operationsRemoteExecutor,
                operationsWebsiteCheckService);
    }

    @Test
    void recoverApacheRunsRemoteRunbookAndValidatesWebsites() {
        ManagedHostEntity host = host();
        ManagedServiceEntity service = service(host);
        ManagedWebsiteEntity website = website(host);
        AtomicLong actionIds = new AtomicLong(40L);
        AtomicLong incidentIds = new AtomicLong(10L);

        when(managedHostRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.of(host));
        when(managedServiceRepository.findFirstByHostIdAndServiceTypeAndActiveTrueOrderByNameAsc(
                3L,
                ManagedServiceType.WEB_SERVER)).thenReturn(Optional.of(service));
        when(operationsIncidentRepository.findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
                eq(3L),
                eq(9L),
                any())).thenReturn(Optional.empty());
        when(operationsIncidentRepository.save(any(OperationsIncidentEntity.class))).thenAnswer(invocation -> {
            OperationsIncidentEntity incident = invocation.getArgument(0);
            if (incident.getId() == null) {
                incident.setId(incidentIds.incrementAndGet());
            }
            return incident;
        });
        when(operationsActionRunRepository.save(any(OperationsActionRunEntity.class))).thenAnswer(invocation -> {
            OperationsActionRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(actionIds.incrementAndGet());
            }
            return run;
        });
        when(operationsRemoteExecutor.execute(eq(host), eq("sudo /usr/local/sbin/atenea-apache-recover"), any()))
                .thenReturn(new RemoteCommandResult(0, "{\"status\":\"ok\"}", ""));
        when(managedWebsiteRepository.findByHostIdAndActiveTrueOrderByNameAsc(3L)).thenReturn(List.of(website));
        when(operationsWebsiteCheckService.check(website, 1500))
                .thenReturn(new WebsiteCheckResponse(
                        30L,
                        "Cliente",
                        "https://cliente.test",
                        200,
                        200,
                        120,
                        1500,
                        1500,
                        "OK",
                        true,
                        null));

        OperationsRecoveryResponse response = operationsService.recoverApacheHungProcesses(3L);

        assertEquals("APACHE_RECOVERY", response.actionRun().action());
        assertEquals(0, response.actionRun().exitCode());
        assertEquals("ok", response.actionRun().report().status());
        assertEquals(OperationsIncidentStatus.RESOLVED, response.incident().status());
        assertEquals(1, response.validationChecks().size());
        verify(operationsWebsiteCheckService, never()).check(website);
    }

    @Test
    void recoverApacheKeepsIncidentMitigatingWhenFastValidationFindsSlowWebsites() {
        ManagedHostEntity host = host();
        ManagedServiceEntity service = service(host);
        ManagedWebsiteEntity website = website(host);
        AtomicLong actionIds = new AtomicLong(40L);
        AtomicLong incidentIds = new AtomicLong(10L);

        when(managedHostRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.of(host));
        when(managedServiceRepository.findFirstByHostIdAndServiceTypeAndActiveTrueOrderByNameAsc(
                3L,
                ManagedServiceType.WEB_SERVER)).thenReturn(Optional.of(service));
        when(operationsIncidentRepository.findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
                eq(3L),
                eq(9L),
                any())).thenReturn(Optional.empty());
        when(operationsIncidentRepository.save(any(OperationsIncidentEntity.class))).thenAnswer(invocation -> {
            OperationsIncidentEntity incident = invocation.getArgument(0);
            if (incident.getId() == null) {
                incident.setId(incidentIds.incrementAndGet());
            }
            return incident;
        });
        when(operationsActionRunRepository.save(any(OperationsActionRunEntity.class))).thenAnswer(invocation -> {
            OperationsActionRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(actionIds.incrementAndGet());
            }
            return run;
        });
        when(operationsRemoteExecutor.execute(eq(host), eq("sudo /usr/local/sbin/atenea-apache-recover"), any()))
                .thenReturn(new RemoteCommandResult(0, "{\"status\":\"ok\"}", ""));
        when(managedWebsiteRepository.findByHostIdAndActiveTrueOrderByNameAsc(3L)).thenReturn(List.of(website));
        when(operationsWebsiteCheckService.check(website, 1500))
                .thenReturn(new WebsiteCheckResponse(
                        30L,
                        "Cliente",
                        "https://cliente.test",
                        200,
                        200,
                        1490,
                        1000,
                        1500,
                        "DEGRADED",
                        false,
                        "Slow response 1490ms above 1000ms threshold"));

        OperationsRecoveryResponse response = operationsService.recoverApacheHungProcesses(3L);

        assertEquals(OperationsIncidentStatus.MITIGATING, response.incident().status());
        assertEquals("DEGRADED", response.validationChecks().getFirst().state());
        assertEquals("Recuperación de Apache ejecutada, pero la validación rápida detecta webs lentas o caídas.",
                response.incident().summary());
    }

    @Test
    void successfulServiceCheckResolvesOpenIncidentForService() {
        ManagedHostEntity host = host();
        ManagedServiceEntity service = service(host);
        OperationsIncidentEntity incident = new OperationsIncidentEntity();
        incident.setId(12L);
        incident.setHost(host);
        incident.setService(service);
        incident.setStatus(OperationsIncidentStatus.OPEN);
        incident.setSeverity(com.atenea.persistence.operations.OperationsSeverity.WARNING);
        incident.setTitle("apache requiere atención");
        incident.setSummary("El diagnóstico remoto de apache terminó con error.");
        incident.setOpenedAt(Instant.parse("2026-05-15T00:04:35Z"));
        incident.setLastActivityAt(Instant.parse("2026-05-15T00:04:35Z"));
        incident.setCreatedAt(Instant.parse("2026-05-15T00:04:35Z"));
        incident.setUpdatedAt(Instant.parse("2026-05-15T00:04:35Z"));

        when(managedHostRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.of(host));
        when(managedServiceRepository.findFirstByHostIdAndServiceTypeAndActiveTrueOrderByNameAsc(
                3L,
                ManagedServiceType.WEB_SERVER)).thenReturn(Optional.of(service));
        when(operationsActionRunRepository.save(any(OperationsActionRunEntity.class))).thenAnswer(invocation -> {
            OperationsActionRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(50L);
            }
            return run;
        });
        when(operationsRemoteExecutor.execute(eq(host), eq("sudo /usr/local/sbin/atenea-apache-status"), any()))
                .thenReturn(new RemoteCommandResult(0, "{\"status\":\"OK\",\"summary\":\"Apache OK\"}", ""));
        when(operationsIncidentRepository.findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
                eq(3L),
                eq(9L),
                any())).thenReturn(Optional.of(incident));
        when(operationsIncidentRepository.save(any(OperationsIncidentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OperationsServiceCheckResponse response = operationsService.checkService(3L, "apache");

        assertEquals(OperationsIncidentStatus.RESOLVED, response.incident().status());
        assertEquals("Diagnóstico actual correcto; incidencia cerrada automáticamente por Atenea.", response.incident().summary());
        verify(operationsIncidentRepository).save(incident);
    }

    @Test
    void hostStatusOpensWarningIncidentWhenWebsiteIsSlow() {
        ManagedHostEntity host = host();
        ManagedServiceEntity service = service(host);
        ManagedWebsiteEntity website = website(host);
        AtomicLong actionIds = new AtomicLong(40L);
        AtomicLong incidentIds = new AtomicLong(10L);

        when(managedHostRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.of(host));
        when(operationsActionRunRepository.save(any(OperationsActionRunEntity.class))).thenAnswer(invocation -> {
            OperationsActionRunEntity run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(actionIds.incrementAndGet());
            }
            return run;
        });
        when(operationsRemoteExecutor.execute(eq(host), eq("sudo /usr/local/sbin/atenea-host-status"), any()))
                .thenReturn(new RemoteCommandResult(0, "{\"status\":\"OK\",\"summary\":\"Host OK\"}", ""));
        when(managedWebsiteRepository.findByHostIdAndActiveTrueOrderByNameAsc(3L)).thenReturn(List.of(website));
        when(operationsWebsiteCheckService.check(website))
                .thenReturn(new WebsiteCheckResponse(
                        30L,
                        "Cliente",
                        "https://cliente.test",
                        200,
                        200,
                        3200,
                        2500,
                        10000,
                        "DEGRADED",
                        false,
                        "Slow response 3200ms above 2500ms threshold"));
        when(managedServiceRepository.findFirstByHostIdAndServiceTypeAndActiveTrueOrderByNameAsc(
                3L,
                ManagedServiceType.WEB_SERVER)).thenReturn(Optional.of(service));
        when(managedServiceRepository.findByHostIdAndActiveTrueOrderByNameAsc(3L)).thenReturn(List.of(service));
        when(operationsIncidentRepository.findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
                eq(3L),
                eq(9L),
                any())).thenReturn(Optional.empty());
        when(operationsIncidentRepository.save(any(OperationsIncidentEntity.class))).thenAnswer(invocation -> {
            OperationsIncidentEntity incident = invocation.getArgument(0);
            if (incident.getId() == null) {
                incident.setId(incidentIds.incrementAndGet());
            }
            return incident;
        });
        when(operationsIncidentRepository.findByStatusInOrderByLastActivityAtDesc(any()))
                .thenReturn(List.of());

        OperationsHostStatusResponse response = operationsService.getHostStatus(3L);

        assertEquals("DEGRADED", response.websiteChecks().getFirst().state());
        verify(operationsIncidentRepository).save(any(OperationsIncidentEntity.class));
    }

    private ManagedHostEntity host() {
        ManagedHostEntity host = new ManagedHostEntity();
        host.setId(3L);
        host.setName("dedicado-principal");
        host.setDescription("Servidor dedicado");
        host.setEnvironment("prod");
        host.setSshHost("dedicado.example");
        host.setSshPort(22);
        host.setSshUser("atenea-ops");
        host.setActive(true);
        host.setCreatedAt(Instant.parse("2026-05-13T09:00:00Z"));
        host.setUpdatedAt(Instant.parse("2026-05-13T09:00:00Z"));
        return host;
    }

    private ManagedServiceEntity service(ManagedHostEntity host) {
        ManagedServiceEntity service = new ManagedServiceEntity();
        service.setId(9L);
        service.setHost(host);
        service.setName("apache");
        service.setServiceType(ManagedServiceType.WEB_SERVER);
        service.setSystemdUnit("apache2");
        service.setProcessPattern("apache2");
        service.setActive(true);
        return service;
    }

    private ManagedWebsiteEntity website(ManagedHostEntity host) {
        ManagedWebsiteEntity website = new ManagedWebsiteEntity();
        website.setId(30L);
        website.setHost(host);
        website.setName("Cliente");
        website.setUrl("https://cliente.test");
        website.setExpectedStatus(200);
        website.setTimeoutMillis(10000);
        website.setDegradedThresholdMillis(2500);
        website.setActive(true);
        return website;
    }
}
