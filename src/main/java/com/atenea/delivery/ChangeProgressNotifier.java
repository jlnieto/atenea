package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.OperatorRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ready means the normal exact-head integration checks passed, not merely PR creation. */
@Service
public class ChangeProgressNotifier {
    private final DeliveryStore store;
    private final MobileDeliveryService delivery;
    private final OperatorRepository operators;
    private final ChangeProgressNotifications notifications;
    private final com.atenea.github.GitHubClient github;
    public ChangeProgressNotifier(DeliveryStore store, MobileDeliveryService delivery,
            OperatorRepository operators, ChangeProgressNotifications notifications, com.atenea.github.GitHubClient github) {
        this.store=store; this.delivery=delivery; this.operators=operators; this.notifications=notifications; this.github=github;
    }
    @Scheduled(scheduler="changeProgressScheduler",fixedDelayString="${atenea.change-progress.poll-ms:30000}")
    @Transactional
    public void observeReadiness() {
        if(!delivery.isEnabled()) return;
        for(var op:store.awaitingIntegrationNotice()) {
            var actor=operators.findById(op.operatorId()).filter(o->o.isActive()
                    && o.getCodexOperationsRole()==com.atenea.persistence.auth.CodexOperationsRole.PLATFORM_ADMINISTRATOR).orElse(null);
            if(actor==null) continue;
            var observation=delivery.observeIntegration(op.sessionId(),new AuthenticatedOperator(actor.getId(),actor.getEmail(),actor.getDisplayName()));
            if(observation.canRequestIntegration() && "MERGEABLE".equals(observation.mergeState())
                    && op.evidence().path("headCommit").asText().equals(observation.sourceCommit())) {
                try {
                    var sessionPr=op.evidence().path("pullRequestUrl").asText();
                    long number=github.extractPullRequestNumber(sessionPr);
                    var session=delivery.publishedSessionForNotice(op);
                    github.requireIntegrationReadiness(new com.atenea.github.GitHubRepositoryRef("jlnieto","atenea"),number,
                            session.getPublishedHeadBranch(),observation.sourceCommit());
                } catch(com.atenea.github.GitHubIntegrationException | DeliveryRejectedException pending) { continue; }
                notifications.record(op,"INTEGRATION_READY");
            }
        }
    }
}
