package com.atenea.mobilepush;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.auth.MobilePushNotificationLogRepository;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobilePushDispatchServiceTest {

    @Mock
    private OperatorPushDeviceRepository operatorPushDeviceRepository;

    @Mock
    private MobilePushNotificationLogRepository mobilePushNotificationLogRepository;

    @Mock
    private ExpoPushSender expoPushSender;

    private MobilePushDispatchService mobilePushDispatchService;

    @BeforeEach
    void setUp() {
        mobilePushDispatchService = new MobilePushDispatchService(
                operatorPushDeviceRepository,
                mobilePushNotificationLogRepository,
                expoPushSender
        );
    }

    @Test
    void notifyRunSucceededSendsAndLogsWhenEventWasNotPreviouslySent() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(55L);
        run.setSession(buildSession(12L, "Atenea", "Inspect project state"));

        when(mobilePushNotificationLogRepository.existsByEventKey("RUN_SUCCEEDED:55")).thenReturn(false);
        when(operatorPushDeviceRepository.findByActiveTrueOrderByUpdatedAtDesc()).thenReturn(List.of(buildDevice()));

        mobilePushDispatchService.notifyRunSucceeded(run);

        verify(expoPushSender).send(any());
        verify(mobilePushNotificationLogRepository).save(any());
    }

    @Test
    void notifyRunSucceededSkipsDispatchWhenEventAlreadyExists() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(55L);
        run.setSession(buildSession(12L, "Atenea", "Inspect project state"));

        when(mobilePushNotificationLogRepository.existsByEventKey("RUN_SUCCEEDED:55")).thenReturn(true);

        mobilePushDispatchService.notifyRunSucceeded(run);

        verify(operatorPushDeviceRepository, never()).findByActiveTrueOrderByUpdatedAtDesc();
        verify(expoPushSender, never()).send(any());
        verify(mobilePushNotificationLogRepository, never()).save(any());
    }

    @Test
    void notifyBillingReadySkipsDispatchWhenNoActiveDevicesExist() {
        SessionDeliverableEntity deliverable = new SessionDeliverableEntity();
        deliverable.setId(91L);
        deliverable.setTitle("Price estimate");
        deliverable.setSession(buildSession(12L, "Atenea", "Inspect project state"));

        when(mobilePushNotificationLogRepository.existsByEventKey("BILLING_READY:91")).thenReturn(false);
        when(operatorPushDeviceRepository.findByActiveTrueOrderByUpdatedAtDesc()).thenReturn(List.of());

        mobilePushDispatchService.notifyBillingReady(deliverable);

        verify(expoPushSender, never()).send(any());
        verify(mobilePushNotificationLogRepository, never()).save(any());
    }

    private static WorkSessionEntity buildSession(Long id, String projectName, String title) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(projectName);
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(id);
        session.setProject(project);
        session.setTitle(title);
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static OperatorPushDeviceEntity buildDevice() {
        OperatorEntity operator = new OperatorEntity();
        operator.setId(3L);
        operator.setEmail("operator@atenea.local");
        operator.setDisplayName("Operator");
        operator.setPasswordHash("hash");
        operator.setActive(true);
        operator.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        operator.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        OperatorPushDeviceEntity device = new OperatorPushDeviceEntity();
        device.setId(4L);
        device.setOperator(operator);
        device.setExpoPushToken("ExponentPushToken[test-token]");
        device.setPlatform("android");
        device.setDeviceId("device-1");
        device.setDeviceName("Pixel");
        device.setAppVersion("1.0.0");
        device.setActive(true);
        device.setLastRegisteredAt(Instant.parse("2026-03-29T11:00:00Z"));
        device.setCreatedAt(Instant.parse("2026-03-29T11:00:00Z"));
        device.setUpdatedAt(Instant.parse("2026-03-29T11:00:00Z"));
        return device;
    }
}
