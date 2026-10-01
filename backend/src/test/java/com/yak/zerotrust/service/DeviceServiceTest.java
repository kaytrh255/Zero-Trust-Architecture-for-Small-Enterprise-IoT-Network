package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.exception.DeviceConflictException;
import com.yak.zerotrust.exception.InvalidDeviceOwnerException;
import com.yak.zerotrust.exception.UserNotFoundException;
import com.yak.zerotrust.mqtt.DeviceSigningKeyPair;
import com.yak.zerotrust.mqtt.MqttDynamicSecurityService;
import com.yak.zerotrust.mqtt.MqttMessageSignatureService;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private MqttMessageSignatureService mqttMessageSignatureService;

    @Mock
    private MqttDynamicSecurityService mqttDynamicSecurityService;

    @Mock
    private DeviceOwnershipAuditService deviceOwnershipAuditService;

    @Mock
    private DeviceStatusAuditService deviceStatusAuditService;

    @Mock
    private DeviceCredentialAuditService deviceCredentialAuditService;

    @Test
    void createsABrokerClientAndReturnsItsCredentialsOnce() {
        UserAccount owner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        String mqttPassword = "A".repeat(43);
        DeviceSigningKeyPair signingKeyPair = new DeviceSigningKeyPair("encoded-public-key", "encoded-private-key");
        when(deviceRepository.existsByDeviceCode("SENSOR-003")).thenReturn(false);
        when(deviceRepository.existsByMqttClientId("SENSOR-003")).thenReturn(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(owner));
        when(deviceCredentialService.issueMqttPassword()).thenReturn(mqttPassword);
        when(mqttMessageSignatureService.generateKeyPair()).thenReturn(signingKeyPair);
        when(mqttMessageSignatureService.fingerprintPublicKey(signingKeyPair.publicKey())).thenReturn("a".repeat(64));
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceProvisioningResponse response = service().create(request(), 7L, "admin");

        assertThat(response.mqttUsername()).isEqualTo("SENSOR-003");
        assertThat(response.mqttPassword()).isEqualTo(mqttPassword);
        assertThat(response.mqttSigningPrivateKey()).isEqualTo(signingKeyPair.privateKey());
        assertThat(response.device().deviceCode()).isEqualTo("SENSOR-003");
        assertThat(response.device().mqttSignatureEnabled()).isTrue();
        verify(deviceCredentialAuditService).recordProvisioning(
                any(Device.class), eq("a".repeat(64)), eq(7L), eq("admin")
        );
        verify(mqttDynamicSecurityService).provisionDevice("SENSOR-003", "SENSOR-003", mqttPassword);
        ArgumentCaptor<Device> deviceCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getLastMqttSequence()).isZero();
        assertThat(deviceCaptor.getValue().getMqttSigningPublicKey()).isEqualTo(signingKeyPair.publicKey());
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
        DeviceSigningKeyPair signingKeyPair = new DeviceSigningKeyPair("rotated-public-key", "rotated-private-key");
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(deviceCredentialService.issueMqttPassword()).thenReturn("B".repeat(43));
        when(mqttMessageSignatureService.generateKeyPair()).thenReturn(signingKeyPair);
        when(mqttMessageSignatureService.fingerprintPublicKey(signingKeyPair.publicKey())).thenReturn("b".repeat(64));

        DeviceProvisioningResponse response = service().rotateMqttCredential(3L, 7L, "admin");

        assertThat(response.mqttUsername()).isEqualTo("SENSOR-003");
        assertThat(response.mqttPassword()).isEqualTo("B".repeat(43));
        assertThat(response.mqttSigningPrivateKey()).isEqualTo("rotated-private-key");
        assertThat(device.getMqttSigningPublicKey()).isEqualTo("rotated-public-key");
        verify(deviceCredentialAuditService).recordRotation(
                device, null, "b".repeat(64), 7L, "admin"
        );
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
    void recordsStatusChangesWithoutChangingBrokerIdentity() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));

        assertThat(service().updateStatus(3L, DeviceStatus.BLOCKED, 7L, "admin").status())
                .isEqualTo(DeviceStatus.BLOCKED);
        verify(deviceStatusAuditService).recordChange(
                device,
                DeviceStatus.ACTIVE,
                DeviceStatus.BLOCKED,
                7L,
                "admin"
        );
        verify(mqttDynamicSecurityService, never()).provisionDevice(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotRecordAnAuditWhenDeviceStatusIsUnchanged() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));

        service().updateStatus(3L, DeviceStatus.ACTIVE, 7L, "admin");

        verify(deviceStatusAuditService, never()).recordChange(any(), any(), any(), any(), anyString());
    }

    @Test
    void recordsRevocationAsADeviceStatusChange() {
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));

        service().revoke(3L, 7L, "admin");

        assertThat(device.getStatus()).isEqualTo(DeviceStatus.REVOKED);
        verify(deviceStatusAuditService).recordChange(
                device,
                DeviceStatus.ACTIVE,
                DeviceStatus.REVOKED,
                7L,
                "admin"
        );
    }

    @Test
    void transfersDeviceOwnershipAndRecordsTheAdminAndOwnerSnapshots() {
        UserAccount previousOwner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        UserAccount newOwner = new UserAccount("student1", "hash", "Student One", UserRole.USER, true);
        setEntityId(previousOwner, 7L);
        setEntityId(newOwner, 8L);
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                previousOwner
        );
        setEntityId(device, 3L);
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(userRepository.findByUsername("student1")).thenReturn(Optional.of(newOwner));

        var response = service().transferOwnership(3L, " Student1 ", 7L, "admin");

        assertThat(response.ownerUsername()).isEqualTo("student1");
        assertThat(device.getOwner()).isSameAs(newOwner);
        verify(deviceOwnershipAuditService).recordTransfer(device, previousOwner, newOwner, 7L, "admin");
    }

    @Test
    void doesNotRecordAnAuditForAnIdempotentOwnershipAssignment() {
        UserAccount owner = new UserAccount("student1", "hash", "Student One", UserRole.USER, true);
        setEntityId(owner, 8L);
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                owner
        );
        setEntityId(device, 3L);
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(device));
        when(userRepository.findByUsername("student1")).thenReturn(Optional.of(owner));

        var response = service().transferOwnership(3L, " Student1 ", 7L, "admin");

        assertThat(response.ownerUsername()).isEqualTo("student1");
        verify(deviceOwnershipAuditService, never()).recordTransfer(any(), any(), any(), any(), anyString());
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

        assertThatThrownBy(() -> service().transferOwnership(3L, "another-admin", 7L, "admin"))
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

        assertThatThrownBy(() -> service().transferOwnership(3L, " Missing-User ", 7L, "admin"))
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

        assertThatThrownBy(() -> service().transferOwnership(3L, "student1", 7L, "admin"))
                .isInstanceOf(InvalidDeviceOwnerException.class);
        assertThat(device.getOwner().getUsername()).isEqualTo("admin");
    }

    private DeviceService service() {
        return new DeviceService(
                deviceRepository,
                userRepository,
                deviceCredentialService,
                mqttMessageSignatureService,
                mqttDynamicSecurityService,
                deviceOwnershipAuditService,
                deviceStatusAuditService,
                deviceCredentialAuditService
        );
    }

    private void setEntityId(Object entity, Long id) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to prepare persisted test entity", exception);
        }
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
