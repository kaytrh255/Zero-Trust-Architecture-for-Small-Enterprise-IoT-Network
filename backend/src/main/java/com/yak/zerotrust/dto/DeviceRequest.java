package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DeviceRequest(
        @NotBlank(message = "Device code is required")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$",
                message = "Device code must be 2-64 characters using letters, numbers, dot, underscore, or hyphen"
        )
        String deviceCode,

        @NotBlank(message = "Device name is required")
        @Size(max = 100, message = "Device name must be at most 100 characters")
        String deviceName,

        @NotNull(message = "Device type is required")
        DeviceType deviceType,

        @NotBlank(message = "IP address is required")
        @Size(max = 45, message = "IP address must be at most 45 characters")
        String ipAddress,

        @NotBlank(message = "MQTT client ID is required")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$",
                message = "MQTT client ID must use letters, numbers, dot, underscore, or hyphen"
        )
        String mqttClientId
) {
}
