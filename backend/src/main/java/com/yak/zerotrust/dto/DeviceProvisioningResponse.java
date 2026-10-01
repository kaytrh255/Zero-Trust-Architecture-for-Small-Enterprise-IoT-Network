package com.yak.zerotrust.dto;

/** Device secrets are returned only when the broker account and signing key are created or rotated. */
public record DeviceProvisioningResponse(
        DeviceResponse device,
        String mqttUsername,
        String mqttPassword,
        String mqttSigningPrivateKey
) {
}
