package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OperationsAlertPolicyTest {
    OperationsMonitoringIssue issue(String key,String message,boolean confirm){return new OperationsMonitoringIssue(key,message,confirm);}
    final OperationsMonitoringIssue ssh=issue("ssh","No se pudo comprobar Apache por SSH (código 255); su estado es desconocido.",true);
    OperationsAlertPolicy.Decision observe(Map<String,OperationsAlertPolicy.IssueState> state,OperationsMonitoringIssue... issues){
        return OperationsAlertPolicy.observe(state,List.of(issues),true);
    }
    @Test void healthyHasNoPushAndClearsARealRecoveredIssue(){assertTrue(observe(Map.of("ssh",new OperationsAlertPolicy.IssueState(3,true))).state().isEmpty());}
    @Test void confirmedApacheOrHttpFailureRepeatsWithConcreteErrorsEveryCycle(){
        var apache=issue("apache","Apache: inactive",false);var web=issue("website:30:DOWN","Web: HTTP 503",false);
        var first=observe(Map.of(),apache,web);var second=observe(first.state(),apache,web);
        assertEquals(List.of(apache,web),first.alerts());assertEquals(first.alerts(),second.alerts());
    }
    @Test void slowWebsiteNeedsThreeConfirmationsThenRepeatsWithoutIncidentDeduplication(){
        var a=issue("website:30:DEGRADED","slow 3100ms",true);
        var one=observe(Map.of(),a);var two=observe(one.state(),a);var three=observe(two.state(),a);
        var four=observe(three.state(),issue(a.key(),"slow 3600ms",true));
        assertTrue(one.alerts().isEmpty());assertTrue(two.alerts().isEmpty());assertEquals(List.of(a),three.alerts());
        assertEquals("slow 3600ms",four.alerts().getFirst().message());
    }
    @Test void isolatedAndIntermittentSshFailuresDoNotNotify(){
        var failed=observe(Map.of(),ssh);var recovered=observe(failed.state());var failedAgain=observe(recovered.state(),ssh);
        assertTrue(failed.alerts().isEmpty());assertTrue(failedAgain.alerts().isEmpty());
    }
    @Test void persistentSshNotifiesOnceThenOnlyRearmsAfterAnObservedRecovery(){
        var one=observe(Map.of(),ssh);var two=observe(one.state(),ssh);var three=observe(two.state(),ssh);var four=observe(three.state(),ssh);
        assertEquals(List.of(ssh),three.alerts());assertTrue(four.alerts().isEmpty());
        var recovered=observe(four.state());var again=observe(observe(observe(recovered.state(),ssh).state(),ssh).state(),ssh);
        assertEquals(List.of(ssh),again.alerts());
    }
    @Test void missingObservationBreaksConsecutiveEvidenceWithoutInventingRecovery(){
        var two=observe(observe(Map.of(),ssh).state(),ssh);
        assertTrue(observe(OperationsAlertPolicy.unobserved(two.state()),ssh).alerts().isEmpty());
        var notified=observe(two.state(),ssh);
        var state=OperationsAlertPolicy.unobserved(notified.state());
        for(int i=0;i<4;i++){var next=observe(state,ssh);assertTrue(next.alerts().isEmpty());state=next.state();}
    }
    @Test void differentWebsitesHaveIndependentCounters(){
        var a=issue("website:30:DEGRADED","Web A slow",true);var b=issue("website:31:DEGRADED","Web B slow",true);
        var one=observe(Map.of(),a);var two=observe(one.state(),a,b);var three=observe(two.state(),b);var four=observe(three.state(),b);
        assertTrue(three.alerts().isEmpty());assertEquals(List.of(b),four.alerts());
    }
    @Test void noDeviceDoesNotMarkVerificationAsNotified(){
        var decision=OperationsAlertPolicy.observe(Map.of("ssh",new OperationsAlertPolicy.IssueState(2,false)),List.of(ssh),false);
        assertTrue(decision.alerts().isEmpty());assertFalse(decision.state().get("ssh").notified());
        assertEquals(List.of(ssh),observe(decision.state(),ssh).alerts());
    }
}
