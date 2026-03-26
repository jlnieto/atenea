package com.atenea.github;

public record GitHubPullRequest(
        long number,
        String htmlUrl,
        String state,
        boolean merged
) {
}
