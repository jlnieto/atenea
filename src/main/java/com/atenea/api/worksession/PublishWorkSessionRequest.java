package com.atenea.api.worksession;

import jakarta.validation.constraints.Size;

public record PublishWorkSessionRequest(
        @Size(max = 200) String commitMessage
) {
}
