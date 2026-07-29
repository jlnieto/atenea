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
        String headSha
) {
    public GitHubPullRequest(long number, String htmlUrl, String state, boolean merged) {
        this(number, htmlUrl, state, merged, null, null, null, null, null);
    }
}
