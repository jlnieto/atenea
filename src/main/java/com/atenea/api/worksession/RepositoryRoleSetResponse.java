package com.atenea.api.worksession;

import com.atenea.persistence.worksession.RepositoryRoleKind;
import com.atenea.persistence.worksession.RepositoryRoleReadiness;
import java.util.List;
import java.util.UUID;

public record RepositoryRoleSetResponse(
        Long workSessionId,
        UUID changeIdentity,
        RepositoryRoleReadiness linkedReadiness,
        List<Role> roles,
        boolean valuesExposed
) {
    public record Role(
            RepositoryRoleKind role,
            String authority,
            String repository,
            String branch,
            String commit,
            String mirrorIdentitySha256,
            String worktreeIdentitySha256,
            String validationProfile,
            RepositoryRoleReadiness readiness
    ) {}
}
