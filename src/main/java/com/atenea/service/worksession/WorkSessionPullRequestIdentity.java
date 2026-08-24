package com.atenea.service.worksession;

import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.util.Objects;

final class WorkSessionPullRequestIdentity {

    private WorkSessionPullRequestIdentity() {
    }

    static void validate(
            WorkSessionEntity session,
            GitHubRepositoryRef repository,
            long expectedNumber,
            GitHubPullRequest pullRequest
    ) {
        String expectedRepository = repository.owner() + "/" + repository.repo();
        String expectedUrl = "https://github.com/" + expectedRepository + "/pull/" + expectedNumber;
        if (pullRequest == null
                || pullRequest.number() != expectedNumber
                || !expectedUrl.equalsIgnoreCase(normalize(pullRequest.htmlUrl()))
                || !expectedRepository.equalsIgnoreCase(normalize(pullRequest.baseRepository()))
                || !expectedRepository.equalsIgnoreCase(normalize(pullRequest.headRepository()))
                || !Objects.equals(session.getBaseBranch(), normalize(pullRequest.baseRef()))
                || !Objects.equals(session.getWorkspaceBranch(), normalize(pullRequest.headRef()))
                || !Objects.equals(session.getFinalCommitSha(), normalize(pullRequest.headSha()))) {
            throw new WorkSessionPublishConflictException(session.getId(),
                    "pull request identity does not match persisted repository, base, head and commit");
        }
    }

    static void validateChangeOwned(
            WorkSessionEntity session,
            GitHubRepositoryRef repository,
            GitHubPullRequest pullRequest
    ) {
        String expectedRepository = repository.owner() + "/" + repository.repo();
        String expectedUrl = pullRequest == null
                ? null
                : "https://github.com/" + expectedRepository + "/pull/" + pullRequest.number();
        if (pullRequest == null
                || pullRequest.number() <= 0
                || !pullRequest.draft()
                || pullRequest.merged()
                || !"open".equalsIgnoreCase(normalize(pullRequest.state()))
                || session.getDevelopmentChange() == null
                || !Objects.equals(session.getDevelopmentChange().getChangeKey(),
                        session.getPublishedChangeKey())
                || !expectedRepository.equalsIgnoreCase(normalize(session.getPublishedRepository()))
                || !expectedUrl.equalsIgnoreCase(normalize(pullRequest.htmlUrl()))
                || !expectedRepository.equalsIgnoreCase(normalize(pullRequest.baseRepository()))
                || !expectedRepository.equalsIgnoreCase(normalize(pullRequest.headRepository()))
                || !Objects.equals(session.getPublishedBaseBranch(), normalize(pullRequest.baseRef()))
                || !Objects.equals(session.getPublishedHeadBranch(), normalize(pullRequest.headRef()))
                || !Objects.equals(session.getFinalCommitSha(), normalize(pullRequest.headSha()))) {
            throw new WorkSessionPublishConflictException(session.getId(),
                    "pull request identity does not match the persisted DevelopmentChange publication");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
