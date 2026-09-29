package com.yak.zerotrust.service;

import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCredentialServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void issuesRandomOneTimeTokensAndStoresOnlyPasswordHashes() {
        DeviceCredentialService service = service();

        DeviceCredentialService.IssuedCredential first = service.issue();
        DeviceCredentialService.IssuedCredential second = service.issue();

        assertThat(first.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(first.passwordHash()).isNotEqualTo(first.token());
        assertThat(passwordEncoder.matches(first.token(), first.passwordHash())).isTrue();
        assertThat(passwordEncoder.matches(second.token(), first.passwordHash())).isFalse();
    }

    @Test
    void deniesDevicesWithoutAProvisionedCredential() {
        DeviceCredentialService service = service();
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001"))
                .thenReturn(Optional.of(device("SENSOR-001")));

        assertThat(service.authenticate("SENSOR-001", "A".repeat(43))).isEmpty();
    }

    @Test
    void bindsTheTokenToTheRegisteredDeviceCode() {
        DeviceCredentialService service = service();
        DeviceCredentialService.IssuedCredential credential = service.issue();
        Device device = device("SENSOR-001");
        device.replaceMqttCredentialHash(credential.passwordHash());
        Device otherDevice = device("CAMERA-001");
        otherDevice.replaceMqttCredentialHash(service.issue().passwordHash());
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001")).thenReturn(Optional.of(device));
        when(deviceRepository.findByDeviceCodeForUpdate("CAMERA-001")).thenReturn(Optional.of(otherDevice));

        assertThat(service.authenticate("sensor-001", credential.token())).contains(device);
        assertThat(service.authenticate("SENSOR-001", "wrong-device-token")).isEmpty();
        assertThat(service.authenticate("CAMERA-001", credential.token())).isEmpty();
    }

    private DeviceCredentialService service() {
        return new DeviceCredentialService(deviceRepository, passwordEncoder);
    }

    private Device device(String code) {
        return new Device(
                code,
                "Test sensor",
                DeviceType.SENSOR,
                "192.168.10.21",
                code,
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
    }
}
