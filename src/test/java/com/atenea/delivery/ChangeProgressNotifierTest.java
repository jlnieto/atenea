package com.atenea.delivery;

import static org.mockito.Mockito.*;
import com.atenea.persistence.auth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ChangeProgressNotifierTest {
    @Test void onlyExactHeadPassingNormalAuthorityCanProduceReadyNotice() {
        var store=mock(DeliveryStore.class);var delivery=mock(MobileDeliveryService.class);
        var operators=mock(OperatorRepository.class);var notices=mock(ChangeProgressNotifications.class);
        var github=mock(com.atenea.github.GitHubClient.class);
        var actor=new OperatorEntity();actor.setId(7L);actor.setActive(true);actor.setEmail("synthetic@atenea.test");actor.setDisplayName("Test");
        actor.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        when(operators.findById(7L)).thenReturn(Optional.of(actor));when(delivery.isEnabled()).thenReturn(true);
        var op=new DeliveryOperation(UUID.randomUUID(),21L,7L,"PUBLISH_PR",DeliveryTarget.APP_PROD,null,UUID.randomUUID(),"SUCCEEDED",null,
                new ObjectMapper().createObjectNode().put("headCommit","a".repeat(40)).put("pullRequestUrl","https://github.com/jlnieto/atenea/pull/42"),null,Instant.now(),Instant.now());
        when(github.extractPullRequestNumber("https://github.com/jlnieto/atenea/pull/42")).thenReturn(42L);
        when(store.awaitingIntegrationNotice()).thenReturn(List.of(op));
        var session=new com.atenea.persistence.worksession.WorkSessionEntity();session.setPublishedHeadBranch("atenea/change-00000000-0000-4000-8000-000000000001");
        when(delivery.publishedSessionForNotice(op)).thenReturn(session);
        var notifier=new ChangeProgressNotifier(store,delivery,operators,notices,github);
        when(delivery.observeIntegration(eq(21L),any())).thenReturn(new MobileDeliveryService.IntegrationObservation(21L,"a".repeat(40),"PENDING",false,null));
        notifier.observeReadiness();verifyNoInteractions(notices);
        when(delivery.observeIntegration(eq(21L),any())).thenReturn(new MobileDeliveryService.IntegrationObservation(21L,"b".repeat(40),"MERGEABLE",true,null));
        notifier.observeReadiness();verifyNoInteractions(notices);
        when(delivery.observeIntegration(eq(21L),any())).thenReturn(new MobileDeliveryService.IntegrationObservation(21L,"a".repeat(40),"MERGEABLE",true,null));
        doThrow(new com.atenea.github.GitHubIntegrationException("CI_PENDING: tests")).when(github).requireIntegrationReadiness(any(),eq(42L),anyString(),anyString());
        notifier.observeReadiness();verifyNoInteractions(notices);
        doNothing().when(github).requireIntegrationReadiness(any(),eq(42L),anyString(),anyString());
        notifier.observeReadiness();verify(notices).record(op,"INTEGRATION_READY");
        verify(delivery,never()).request(any(),any(),any(),any());
    }
}
