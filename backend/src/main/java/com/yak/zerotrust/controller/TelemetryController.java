package com.yak.zerotrust.controller;

import com.yak.zerotrust.dto.TelemetryResponse;
import com.yak.zerotrust.service.TelemetryQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryQueryService telemetryQueryService;

    public TelemetryController(TelemetryQueryService telemetryQueryService) {
        this.telemetryQueryService = telemetryQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ANALYST')")
    public List<TelemetryResponse> getRecent() {
        return telemetryQueryService.getRecent();
    }
}
