package com.yak.zerotrust.dto;

/** MQTT credentials are returned only when the broker account is created or rotated. */
public record DeviceProvisioningResponse(
        DeviceResponse device,
        String mqttUsername,
        String mqttPassword
) {
}
