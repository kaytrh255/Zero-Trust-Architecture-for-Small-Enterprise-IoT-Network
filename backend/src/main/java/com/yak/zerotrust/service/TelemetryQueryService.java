package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.TelemetryResponse;
import com.yak.zerotrust.entity.DeviceTelemetry;
import com.yak.zerotrust.repository.DeviceTelemetryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TelemetryQueryService {

    private final DeviceTelemetryRepository telemetryRepository;

    public TelemetryQueryService(DeviceTelemetryRepository telemetryRepository) {
        this.telemetryRepository = telemetryRepository;
    }

    @Transactional(readOnly = true)
    public List<TelemetryResponse> getRecent() {
        return telemetryRepository.findTop100ByOrderByReceivedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TelemetryResponse> getRecentForDevice(String deviceCode) {
        return telemetryRepository.findTop100ByDevice_DeviceCodeOrderByReceivedAtDesc(deviceCode).stream()
                .map(this::toResponse)
                .toList();
    }

    private TelemetryResponse toResponse(DeviceTelemetry telemetry) {
        return new TelemetryResponse(
                telemetry.getId(),
                telemetry.getDeviceCode(),
                telemetry.getMetric(),
                telemetry.getValue(),
                telemetry.getUnit(),
                telemetry.getMeasuredAt(),
                telemetry.getReceivedAt()
        );
    }
}
