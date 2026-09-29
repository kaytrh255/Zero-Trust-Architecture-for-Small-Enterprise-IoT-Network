package com.yak.zerotrust.access;

import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.UserRole;

public record AccessContext(
        Long requesterId,
        String requesterUsername,
        UserRole requesterRole,
        AccessChannel channel,
        String deviceCode,
        Long deviceId,
        DeviceType deviceType,
        DeviceStatus deviceStatus,
        String resource,
        PolicyAction action
) {

    public AccessContext withDevice(Device device) {
        return new AccessContext(
                requesterId,
                requesterUsername,
                requesterRole,
                channel,
                device.getDeviceCode(),
                device.getId(),
                device.getDeviceType(),
                device.getStatus(),
                resource,
                action
        );
    }
}
