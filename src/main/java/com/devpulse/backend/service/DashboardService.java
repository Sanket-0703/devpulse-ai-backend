package com.devpulse.backend.service;

import com.devpulse.backend.dto.dashboard.DashboardResponse;

public interface DashboardService {

    DashboardResponse summary(Long userId);
}
