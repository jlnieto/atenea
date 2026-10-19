package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubReleaseBuildTest {
    final ObjectMapper mapper=new ObjectMapper();final String source="a".repeat(40);
    @Test void legacyFailurePreservesExactRunAndPhaseWithoutInventingMessages() throws Exception {
        var github=mock(GitHubClient.class);
        when(github.buildApi(contains("/workflows/"))).thenReturn(mapper.readTree("""
            {"workflow_runs":[{"id":91,"run_attempt":2,"head_sha":"%s","head_branch":"main","event":"push",
              "path":".github/workflows/release-artifacts-v1.yml","head_repository":{"full_name":"jlnieto/atenea"},
              "status":"completed","conclusion":"failure"}]}
            """.formatted(source)));
        when(github.buildApi(contains("/jobs?"))).thenReturn(mapper.readTree("""
            {"total_count":1,"jobs":[{"name":"backend","conclusion":"failure","steps":[
              {"name":"Backend tests in the job-owned database","conclusion":"failure"}]}]}
            """));
        when(github.buildApi(contains("/artifacts?"))).thenReturn(mapper.readTree("{\"total_count\":0,\"artifacts\":[]}"));
        var result=new GitHubReleaseBuild(github).observe(source);
        assertEquals("FAILED",result.state());assertEquals(91L,result.runId());assertEquals(2,result.attempt());
        assertEquals("BACKEND_TEST_COMPILE_OR_TEST",result.phase());assertTrue(result.failures().isEmpty());assertNull(result.diagnosticSha256());
        assertEquals("https://github.com/jlnieto/atenea/actions/runs/91",result.url());
        verify(github,never()).buildDiagnostic(anyLong(),anyString());
    }
    @Test void structuralDiagnosticHasOnlyBoundedFailureIdentities() throws Exception {
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree("""
            {"protocol":"atenea-build-diagnostic/v1","target":"APP_PROD","sourceCommit":"%s",
              "failures":[{"kind":"COMPILATION","path":"src/test/java/com/atenea/Test.java","line":71}]}
            """.formatted(source));
        var parsed=GitHubReleaseBuild.parse(value,source);
        assertEquals(1,parsed.size());assertEquals(71,parsed.getFirst().line());
        for(var bad:List.of(value.deepCopy().put("message","secret"),value.deepCopy().put("sourceCommit","b".repeat(40))))
            assertThrows(GitHubIntegrationException.class,()->GitHubReleaseBuild.parse(bad,source));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("failures").get(0)).put("path","../../etc/token");
        assertThrows(GitHubIntegrationException.class,()->GitHubReleaseBuild.parse(value,source));
    }
    @Test void foreignOrTruncatedRunsNeverBecomeReady() throws Exception {
        var github=mock(GitHubClient.class);
        when(github.buildApi(any())).thenReturn(mapper.readTree("{\"workflow_runs\":[{\"id\":1,\"head_sha\":\""+source+"\",\"status\":\"completed\",\"conclusion\":\"success\"}]}"));
        assertEquals("PENDING",new GitHubReleaseBuild(github).observe(source).state());
        assertThrows(GitHubIntegrationException.class,()->new GitHubReleaseBuild(github).observe("main"));
    }
}
