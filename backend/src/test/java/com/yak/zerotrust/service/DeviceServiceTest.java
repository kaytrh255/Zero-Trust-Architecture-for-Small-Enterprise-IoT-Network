package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DeviceCredentialService deviceCredentialService;

    @Test
    void returnsTheNewDeviceTokenOnceAndPersistsOnlyItsHash() {
        UserAccount owner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        String deviceToken = "A".repeat(43);
        when(deviceRepository.existsByDeviceCode("SENSOR-003")).thenReturn(false);
        when(deviceRepository.existsByMqttClientId("SENSOR-003")).thenReturn(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(owner));
        when(deviceCredentialService.issue()).thenReturn(
                new DeviceCredentialService.IssuedCredential(deviceToken, "bcrypt-hash")
        );
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));
        DeviceService service = service();

        DeviceProvisioningResponse response = service.create(request(), 7L);

        assertThat(response.deviceToken()).isEqualTo(deviceToken);
        assertThat(response.device().deviceCode()).isEqualTo("SENSOR-003");
        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getMqttCredentialHash()).isEqualTo("bcrypt-hash");
        assertThat(deviceCaptor.getValue().getMqttCredentialHash()).isNotEqualTo(deviceToken);
    }

    @Test
    void credentialRotationReplacesTheStoredHashAndReturnsTheNewSecretOnce() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        device.replaceMqttCredentialHash("old-hash");
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(deviceCredentialService.issue()).thenReturn(
                new DeviceCredentialService.IssuedCredential("B".repeat(43), "new-hash")
        );

        DeviceProvisioningResponse response = service().rotateMqttCredential(3L);

        assertThat(response.deviceToken()).isEqualTo("B".repeat(43));
        assertThat(device.getMqttCredentialHash()).isEqualTo("new-hash");
        assertThat(device.getMqttCredentialHash()).isNotEqualTo("old-hash");
    }

    private DeviceService service() {
        return new DeviceService(deviceRepository, userRepository, deviceCredentialService);
    }

    private DeviceRequest request() {
        return new DeviceRequest(
                "sensor-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003"
        );
    }
}
