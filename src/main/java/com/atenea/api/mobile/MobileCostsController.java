package com.atenea.api.mobile;

import com.atenea.service.costs.ApiCostsService;
import java.util.List;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/costs")
public class MobileCostsController {

    private final ApiCostsService apiCostsService;

    public MobileCostsController(ApiCostsService apiCostsService) {
        this.apiCostsService = apiCostsService;
    }

    @GetMapping("/overview")
    public MobileApiCostsOverviewResponse getOverview(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        if (startDate != null || endDate != null) {
            return apiCostsService.getOverview(startDate, endDate);
        }
        return apiCostsService.getOverview(days);
    }

    @GetMapping("/codex-auth")
    public List<MobileCodexAuthStatusResponse> getCodexAuthStatus() {
        return apiCostsService.getCodexAuthStatuses();
    }
}
