package com.atenea.github;

public record GitHubPullRequest(
        long number,
        String htmlUrl,
        String state,
        boolean merged,
        String baseRepository,
        String baseRef,
        String headRepository,
        String headRef,
        String headSha,
        boolean draft
) {
    public GitHubPullRequest(long number, String htmlUrl, String state, boolean merged) {
        this(number, htmlUrl, state, merged, null, null, null, null, null, false);
    }

    public GitHubPullRequest(
            long number,
            String htmlUrl,
            String state,
            boolean merged,
            String baseRepository,
            String baseRef,
            String headRepository,
            String headRef,
            String headSha) {
        this(number, htmlUrl, state, merged, baseRepository, baseRef,
                headRepository, headRef, headSha, false);
    }
}
