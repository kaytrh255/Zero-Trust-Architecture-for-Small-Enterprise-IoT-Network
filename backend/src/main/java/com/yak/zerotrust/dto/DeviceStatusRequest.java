package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.DeviceStatus;
import jakarta.validation.constraints.NotNull;

public record DeviceStatusRequest(
        @NotNull(message = "Device status is required")
        DeviceStatus status
) {
}
