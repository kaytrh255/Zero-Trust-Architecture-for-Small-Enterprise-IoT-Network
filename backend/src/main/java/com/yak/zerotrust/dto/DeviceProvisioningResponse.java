package com.yak.zerotrust.dto;

/** Contains a device token only when it is first issued or rotated. */
public record DeviceProvisioningResponse(
        DeviceResponse device,
        String deviceToken
) {
}
