package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubChecksProgressTest {
    final ObjectMapper mapper=new ObjectMapper();
    final GitHubClient github=mock(GitHubClient.class);
    final GitHubServiceHealth health=mock(GitHubServiceHealth.class);
    final String source="a".repeat(40), branch="atenea/change-00000000-0000-4000-8000-000000000001";
    @BeforeEach void healthy() { when(health.observe()).thenReturn("OPERATIONAL"); }
    GitHubChecksProgress reader() { return new GitHubChecksProgress(github,health); }
    void run(String status,String conclusion) throws Exception {
        when(github.buildApi(contains("/workflows/"))).thenReturn(mapper.readTree("""
          {"workflow_runs":[{"id":91,"head_sha":"%s","head_branch":"%s","event":"push",
          "path":".github/workflows/ufd-validation-v1.yml","head_repository":{"full_name":"jlnieto/atenea"},
          "status":"%s","conclusion":"%s"}]}
          """.formatted(source,branch,status,conclusion)));
    }
    void job(String status,String conclusion) throws Exception {
        when(github.buildApi(contains("/jobs?"))).thenReturn(mapper.readTree("""
          {"total_count":2,"jobs":[{"name":"ufd-validation","status":"%s","conclusion":"%s",
            "started_at":"2026-10-19T15:31:56Z","completed_at":"2026-10-19T15:35:08Z"},
            {"name":"ufd-owned-head","status":"completed","conclusion":"skipped"}]}
          """.formatted(status,conclusion)));
    }
    @Test void passedJobsWithUnclosedRunAreWaitingNotFailureOrPermission() throws Exception {
        run("in_progress","");job("completed","success");when(health.observe()).thenReturn("DEGRADED");
        var value=reader().observe(source,branch,false,false);
        assertEquals("CLOSING",value.state());assertEquals("DEGRADED",value.serviceState());
        assertEquals(1,value.checks().size());assertEquals("PASSED",value.checks().getFirst().state());
        assertEquals(192,value.checks().getFirst().finishedAt()-value.checks().getFirst().startedAt());
        verify(github,never()).requireUfdValidation(any(),any(),any());
    }
    @Test void queueRunningFailureAndCompletedAreDistinct() throws Exception {
        for(var status:new String[]{"queued","in_progress","completed"}) {
            run(status,status.equals("completed") ? "success" : "");
            job(status,status.equals("completed") ? "success" : "");
            assertEquals(switch(status){case "queued"->"QUEUED";case "in_progress"->"RUNNING";default->"PASSED";},
                    reader().observe(source,branch,false,false).state());
        }
        run("completed","failure");job("completed","failure");
        assertEquals("FAILED",reader().observe(source,branch,false,false).state());
    }
    @Test void providerReadFailureIsNotTestFailureAndRepeatedPollsUseCache() throws Exception {
        when(github.buildApi(anyString())).thenThrow(new GitHubIntegrationException("TRANSPORT"));
        var reader=reader();var first=reader.observe(source,branch,false,false);
        assertEquals("UNAVAILABLE",first.state());assertSame(first,reader.observe(source,branch,false,false));
        verify(github,times(1)).buildApi(anyString());
    }
    @Test void foreignHeadCannotBecomeSuccessfulAndMissingRunIsNotInventedAsQueued() throws Exception {
        run("completed","success");job("completed","success");
        var value=reader().observe("b".repeat(40),branch,false,false);
        assertEquals("NOT_STARTED",value.state());assertTrue(value.checks().stream().noneMatch(c->c.state().equals("PASSED")));
    }
    @Test void backendPassedAgainstPreviousMainCannotDescribeCurrentBaseAsPassed() throws Exception {
        run("completed","success");job("completed","success");
        when(github.canonicalMain(any())).thenReturn("b".repeat(40));
        when(github.buildApi(contains("backend-merge-validation-v1.yml/runs"))).thenReturn(mapper.readTree("""
            {"workflow_runs":[{"id":92,"head_sha":"%s","head_branch":"%s","event":"pull_request",
              "path":".github/workflows/backend-merge-validation-v1.yml","head_repository":{"full_name":"jlnieto/atenea"},
              "status":"completed","conclusion":"success"}]}
            """.formatted(source,branch)));
        when(github.buildApi(contains("/runs/92/jobs?"))).thenReturn(mapper.readTree("""
            {"total_count":1,"jobs":[{"name":"backend-merge-%s","status":"completed","conclusion":"success"}]}
            """.formatted("c".repeat(40))));
        var value=reader().observe(source,branch,false,true);
        assertEquals("UNAVAILABLE",value.state());
        assertEquals("UNAVAILABLE",value.checks().getLast().state());
    }
    @Test void unknownJobTextAndTruncatedEvidenceNeverLeakOrClaimSuccess() throws Exception {
        run("completed","success");
        for(var jobs:new String[]{"{\"total_count\":101,\"jobs\":[]}",
                "{\"total_count\":1,\"jobs\":[{\"name\":\"/etc/token private\",\"status\":\"completed\",\"conclusion\":\"success\"}]}"}) {
            when(github.buildApi(contains("/jobs?"))).thenReturn(mapper.readTree(jobs));
            var result=reader().observe(source,branch,false,false);
            assertEquals("UNAVAILABLE",result.state());assertTrue(result.checks().isEmpty());
        }
    }
    @Test void inputsCannotChooseArbitraryRepositoryPathOrRef() {
        for(var bad:new String[]{"main","../secret","feature/random","atenea/change-not-a-uuid"})
            assertThrows(GitHubIntegrationException.class,()->reader().observe(source,bad,false,false));
        assertThrows(GitHubIntegrationException.class,()->reader().observe("main",branch,false,false));
        verifyNoInteractions(github,health);
    }
    @Test void statusAdvisoryNeverInventsIncidentOrExposesProviderText() throws Exception {
        assertEquals("DEGRADED",GitHubServiceHealth.parse(mapper.readTree("{\"components\":[{\"name\":\"Actions\",\"status\":\"degraded_performance\"}]}")));
        assertEquals("OPERATIONAL",GitHubServiceHealth.parse(mapper.readTree("{\"components\":[{\"name\":\"Actions\",\"status\":\"operational\"}]}")));
        assertEquals("UNKNOWN",GitHubServiceHealth.parse(mapper.readTree("{}")));
        assertEquals("UNKNOWN",GitHubServiceHealth.parse(mapper.readTree("{\"components\":[{\"name\":\"Actions\",\"status\":\"unexpected\"}]}")));
    }
}
