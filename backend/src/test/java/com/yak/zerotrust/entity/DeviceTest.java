package com.yak.zerotrust.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTest {

    @Test
    void newDevicesStartActiveAndCanBeBlockedOrRevoked() {
        UserAccount owner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        Device device = new Device(
                "SENSOR-TEST",
                "Test sensor",
                DeviceType.SENSOR,
                "192.168.1.10",
                "SENSOR-TEST",
                owner
        );

        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);

        device.changeStatus(DeviceStatus.BLOCKED);
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.BLOCKED);

        device.changeStatus(DeviceStatus.REVOKED);
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.REVOKED);
    }
}
