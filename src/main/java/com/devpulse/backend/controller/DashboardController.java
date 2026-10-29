package com.devpulse.backend.controller;

import com.devpulse.backend.dto.dashboard.DashboardResponse;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "The signed-in user's overview")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "Getting-started steps, counts, open findings, recent reviews and AI usage")
    public DashboardResponse summary(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return dashboardService.summary(principal.getId());
    }
}
