package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.DeviceTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceTelemetryRepository extends JpaRepository<DeviceTelemetry, Long> {

    List<DeviceTelemetry> findTop100ByOrderByReceivedAtDesc();
}
