package com.atenea.api.project;

import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import java.util.List;

public record ProjectApprovedPriceEstimatesResponse(
        Long projectId,
        List<ApprovedPriceEstimateSummaryResponse> approvedPriceEstimates
) {
}
