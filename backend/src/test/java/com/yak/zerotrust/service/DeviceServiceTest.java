package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.exception.DeviceConflictException;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.exception.InvalidDeviceOwnerException;
import com.yak.zerotrust.exception.UserNotFoundException;
import com.yak.zerotrust.mqtt.MqttDynamicSecurityService;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    @Mock
    private MqttDynamicSecurityService mqttDynamicSecurityService;

    @Test
    void createsABrokerClientAndReturnsItsCredentialsOnce() {
        UserAccount owner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        String mqttPassword = "A".repeat(43);
        when(deviceRepository.existsByDeviceCode("SENSOR-003")).thenReturn(false);
        when(deviceRepository.existsByMqttClientId("SENSOR-003")).thenReturn(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(owner));
        when(deviceCredentialService.issueMqttPassword()).thenReturn(mqttPassword);
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceProvisioningResponse response = service().create(request(), 7L);

        assertThat(response.mqttUsername()).isEqualTo("SENSOR-003");
        assertThat(response.mqttPassword()).isEqualTo(mqttPassword);
        assertThat(response.device().deviceCode()).isEqualTo("SENSOR-003");
        verify(mqttDynamicSecurityService).provisionDevice("SENSOR-003", "SENSOR-003", mqttPassword);
        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getLastMqttSequence()).isZero();
    }

    @Test
    void rotationChangesTheBrokerPasswordAndReturnsItOnce() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(deviceCredentialService.issueMqttPassword()).thenReturn("B".repeat(43));

        DeviceProvisioningResponse response = service().rotateMqttCredential(3L);

        assertThat(response.mqttUsername()).isEqualTo("SENSOR-003");
        assertThat(response.mqttPassword()).isEqualTo("B".repeat(43));
        verify(mqttDynamicSecurityService).provisionDevice("SENSOR-003", "SENSOR-003", "B".repeat(43));
    }

    @Test
    void preventsChangingBrokerIdentityAfterProvisioning() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        DeviceRequest changedIdentity = new DeviceRequest(
                "SENSOR-004",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-004"
        );

        assertThatThrownBy(() -> service().update(3L, changedIdentity))
                .isInstanceOf(DeviceConflictException.class)
                .hasMessageContaining("cannot change");
        verify(mqttDynamicSecurityService, never()).provisionDevice(anyString(), anyString(), anyString());
    }

    @Test
    void keepsBrokerIdentityAvailableForBackendStatusAuditing() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));

        assertThat(service().updateStatus(3L, DeviceStatus.BLOCKED).status()).isEqualTo(DeviceStatus.BLOCKED);
        verify(mqttDynamicSecurityService, never()).provisionDevice(anyString(), anyString(), anyString());
    }

    @Test
    void transfersDeviceOwnershipToAnEnabledUserUsingNormalizedUsername() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        UserAccount newOwner = new UserAccount("student1", "hash", "Student One", UserRole.USER, true);
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(userRepository.findByUsername("student1")).thenReturn(Optional.of(newOwner));

        var response = service().transferOwnership(3L, " Student1 ");

        assertThat(response.ownerUsername()).isEqualTo("student1");
        assertThat(device.getOwner()).isSameAs(newOwner);
    }

    @Test
    void rejectsPrivilegedDeviceOwners() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        UserAccount admin = new UserAccount("another-admin", "hash", "Another Admin", UserRole.ADMIN, true);
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(userRepository.findByUsername("another-admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service().transferOwnership(3L, "another-admin"))
                .isInstanceOf(InvalidDeviceOwnerException.class);
        assertThat(device.getOwner().getUsername()).isEqualTo("admin");
    }

    @Test
    void rejectsUnknownDeviceOwnersAfterNormalizingTheUsername() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(userRepository.findByUsername("missing-user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().transferOwnership(3L, " Missing-User "))
                .isInstanceOf(UserNotFoundException.class);
        assertThat(device.getOwner().getUsername()).isEqualTo("admin");
    }

    @Test
    void rejectsDisabledDeviceOwners() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        UserAccount disabledUser = new UserAccount("student1", "hash", "Student One", UserRole.USER, false);
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(userRepository.findByUsername("student1")).thenReturn(Optional.of(disabledUser));

        assertThatThrownBy(() -> service().transferOwnership(3L, "student1"))
                .isInstanceOf(InvalidDeviceOwnerException.class);
        assertThat(device.getOwner().getUsername()).isEqualTo("admin");
    }

    private DeviceService service() {
        return new DeviceService(
                deviceRepository,
                userRepository,
                deviceCredentialService,
                mqttDynamicSecurityService
        );
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
