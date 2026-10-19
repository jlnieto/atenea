package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.*;
import com.atenea.github.*;
import com.atenea.persistence.auth.*;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AteneaUpdatesLoaderTest {
    private final GitHubClient github=mock(GitHubClient.class);
    private final ReleaseControlClient release=mock(ReleaseControlClient.class);
    private final OperatorRepository operators=mock(OperatorRepository.class);
    private final RemoteWorkerClient worker=mock(RemoteWorkerClient.class);
    private final GitHubRepositoryRef app=new GitHubRepositoryRef("jlnieto","atenea");
    private final GitHubRepositoryRef platform=new GitHubRepositoryRef("jlnieto","atenea-remote-worker-spec");
    private final AuthenticatedOperator actor=new AuthenticatedOperator(5L,"test@example.invalid","Test");
    private final AteneaUpdatesLoader service=new AteneaUpdatesLoader(github,release,worker,new ProductNewsCopy(new ObjectMapper()));
    private final ObjectMapper mapper=new ObjectMapper();
    private final String source="8".repeat(40),head="6".repeat(40),merge="7".repeat(40);

    @BeforeEach void fixture() {
        var operator=new OperatorEntity();operator.setActive(true);when(operators.findById(5L)).thenReturn(Optional.of(operator));
        when(release.observeApp()).thenReturn(observation("APP_PROD"));when(release.observeAndroid()).thenReturn(observation("ANDROID_STABLE"));
        when(release.observePlatform()).thenReturn(observation("AX42_PLATFORM"));
        var health=mock(RemoteWorkerClient.Health.class);when(health.workerId()).thenReturn("ax42-01");when(health.healthy()).thenReturn(true);when(worker.health()).thenReturn(health);
        when(github.recentMergedChanges(eq(app),anyInt())).thenReturn(new GitHubClient.MergedChangesPage(List.of(change(10)),false));
        when(github.recentMergedChanges(eq(platform),anyInt())).thenReturn(new GitHubClient.MergedChangesPage(List.of(change(11)),false));
        when(github.changedFiles(app,10,"feature/news",head)).thenReturn(List.of("src/main/Example.java","android/app/Example.kt"));
    }
    private GitHubClient.MergedChange change(long number) {
        return new GitHubClient.MergedChange(number,"Mejoras de la conversación","Las imágenes funcionan en la misma conversación.",
                "feature/news",head,merge,Instant.now().minusSeconds(number),"https://github.com/jlnieto/atenea/pull/"+number);
    }
    private ObjectNode observation(String target) {
        String protocol=switch(target) {case "APP_PROD" -> "atenea-app-observation/v1";case "ANDROID_STABLE" -> "atenea-android-observation/v1";default -> "atenea-platform-observation/v1";};
        var value=mapper.createObjectNode().put("protocol",protocol).put("target",target).put("state","OBSERVED").put("sourceCommit",source)
                .put("observedAt",Instant.now().getEpochSecond()).put("finishedAt",1L).put("planId",UUID.randomUUID().toString())
                .put("operationId",UUID.randomUUID().toString()).put("receiptSha256","a".repeat(64));
        if (target.equals("APP_PROD")) value.put("imageSha256","b".repeat(64)).put("healthy",true);
        else if (target.equals("ANDROID_STABLE")) value.put("apkSha256","b".repeat(64)).put("certificateSha256","c".repeat(64)).put("versionCode",1000030).put("versionName","0.6.30");
        else value.put("installationSha256","b".repeat(64));
        return value;
    }
    @Test void globalHistorySeparatesEveryComponentAndUsesVerifiedInclusionWithoutPublishing() {
        var result=service.observe(1);assertEquals("atenea-updates/v1",result.protocolVersion());assertEquals("CURRENT",result.historyState());
        assertEquals(2,result.entries().size());assertEquals("app:10",result.entries().getFirst().id());
        assertEquals(Set.of("APP_PROD","ANDROID_STABLE"),result.entries().getFirst().publications().keySet());
        assertEquals("PUBLISHED",result.entries().getFirst().publications().get("ANDROID_STABLE"));
        assertEquals(Map.of("AX42_PLATFORM","PUBLISHED"),result.entries().getLast().publications());
        assertEquals("0.6.30",result.android().versionName());assertEquals(source,result.platform().sourceCommit());
        verify(release,never()).plan(any(),any(),any());verify(release,never()).execute(any(),any(),any());
        verify(worker,never()).cancel(any());
    }
    @Test void mergedIsNotPublishedWhenCurrentSourceDoesNotContainTheChange() {
        doThrow(new GitHubIntegrationException("RELEASE_CHANGE_NOT_INCLUDED")).when(github)
                .requireIntegratedChangeInSource(app,10,"feature/news",head,merge,source);
        var result=service.observe(1);assertTrue(result.entries().getFirst().publications().values().stream().allMatch("NOT_INCLUDED"::equals));
    }
    @Test void unobservedApkCannotInheritHealthyBackendPublication() {
        when(release.observeAndroid()).thenThrow(new DeliveryRejectedException("ANDROID_OBSERVATION_EVIDENCE_MISMATCH"));
        var result=service.observe(1);assertNull(result.android());assertTrue(result.unavailable().contains("ANDROID_OBSERVATION_UNAVAILABLE"));
        assertEquals("UNAVAILABLE",result.entries().getFirst().publications().get("ANDROID_STABLE"));
        assertEquals("PUBLISHED",result.entries().getFirst().publications().get("APP_PROD"));
    }
    @Test void uncertainGitHubEvidenceIsNotCachedAsDeliveredOrMissing() {
        doThrow(new GitHubIntegrationException("UPSTREAM_UNAVAILABLE")).doNothing().when(github)
                .requireIntegratedChangeInSource(app,10,"feature/news",head,merge,source);
        var first=service.observe(1);assertTrue(first.entries().getFirst().publications().containsValue("UNAVAILABLE"));
        var second=service.observe(1);assertTrue(second.entries().getFirst().publications().values().stream().allMatch("PUBLISHED"::equals));
    }
    @Test void currentObservationsAreRefreshedEvenWhenHistoryAndImmutableAncestryAreCached() {
        service.observe(1);when(release.observeApp()).thenReturn(observation("APP_PROD").put("healthy",false));
        var result=service.observe(1);assertEquals("PUBLISHED_NEEDS_ATTENTION",result.entries().getFirst().publications().get("APP_PROD"));
        verify(github,times(1)).recentMergedChanges(app,1);verify(release,times(2)).observeApp();
    }
    @Test void historyOrClassificationFailureIsExplicitInsteadOfInventingSuccess() {
        when(github.changedFiles(app,10,"feature/news",head)).thenThrow(new GitHubIntegrationException("DELIVERY_DIFF_INCOMPLETE"));
        assertNull(service.observe(1).entries().getFirst().requiredTargets());
        when(github.recentMergedChanges(app,2)).thenThrow(new GitHubIntegrationException("UNAVAILABLE"));
        var result=service.observe(2);assertEquals("UNAVAILABLE",result.historyState());assertTrue(result.entries().isEmpty());assertNotNull(result.android());
    }
    @Test void pageAndOperatorAreClosedBeforeAnyTransportCall() {
        for (int page:List.of(0,51,Integer.MAX_VALUE)) assertThrows(DeliveryRejectedException.class,() -> service.observe(page));
        verifyNoInteractions(github,release,worker);
    }
    @Test void lastAllowedPageDoesNotAdvertiseAnUnreachablePage() {
        when(github.recentMergedChanges(app,50)).thenReturn(new GitHubClient.MergedChangesPage(List.of(change(10)),true));
        assertNull(service.observe(50).nextPage());
    }
    @Test void featuredHistoricalSshFixIsVerifiedAndNeverDuplicatedWhenAlsoOnTheCurrentPage() {
        var note=new ProductNewsCopy(mapper).featured().getFirst();
        var historical=new GitHubClient.MergedChange(72,"ajustar alertas apa he","internal","feature/alerts",head,note.mergeCommit(),Instant.now().minusSeconds(100),"https://github.com/jlnieto/atenea/pull/72");
        when(github.mergedChange(app,72)).thenReturn(historical);
        when(github.recentMergedChanges(app,1)).thenReturn(new GitHubClient.MergedChangesPage(List.of(historical),false));
        when(github.changedFiles(app,72,"feature/alerts",head)).thenReturn(List.of("src/main/Monitor.java"));
        var result=service.observe(1);assertEquals(1,result.highlights().size());assertEquals("app:72",result.highlights().getFirst().id());
        assertEquals("PUBLISHED",result.highlights().getFirst().publications().get("APP_PROD"));
        assertTrue(result.entries().stream().noneMatch(e->e.id().equals("app:72")));
    }
}
