package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.DeviceOwnershipAuditResponse;
import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.dto.DeviceResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.exception.DeviceConflictException;
import com.yak.zerotrust.exception.DeviceNotFoundException;
import com.yak.zerotrust.exception.InvalidDeviceOwnerException;
import com.yak.zerotrust.exception.UserNotFoundException;
import com.yak.zerotrust.mqtt.MqttDynamicSecurityService;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final DeviceCredentialService deviceCredentialService;
    private final MqttDynamicSecurityService mqttDynamicSecurityService;
    private final DeviceOwnershipAuditService deviceOwnershipAuditService;

    public DeviceService(
            DeviceRepository deviceRepository,
            UserRepository userRepository,
            DeviceCredentialService deviceCredentialService,
            MqttDynamicSecurityService mqttDynamicSecurityService,
            DeviceOwnershipAuditService deviceOwnershipAuditService
    ) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.deviceCredentialService = deviceCredentialService;
        this.mqttDynamicSecurityService = mqttDynamicSecurityService;
        this.deviceOwnershipAuditService = deviceOwnershipAuditService;
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getAll() {
        return deviceRepository.findAllByOrderByDeviceCodeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse getById(Long id) {
        return toResponse(findDevice(id));
    }

    @Transactional(readOnly = true)
    public List<DeviceOwnershipAuditResponse> getOwnershipAudits(Long id) {
        findDevice(id);
        return deviceOwnershipAuditService.getRecentForDevice(id);
    }

    @Transactional
    public DeviceProvisioningResponse create(DeviceRequest request, Long ownerId) {
        String deviceCode = normalizeCode(request.deviceCode());
        String mqttClientId = request.mqttClientId().trim();
        ensureUnique(deviceCode, mqttClientId);

        UserAccount owner = userRepository.findById(ownerId)
                .orElseThrow(UserNotFoundException::new);
        String mqttPassword = deviceCredentialService.issueMqttPassword();
        Device savedDevice = deviceRepository.save(new Device(
                deviceCode,
                request.deviceName().trim(),
                request.deviceType(),
                request.ipAddress().trim(),
                mqttClientId,
                owner
        ));
        mqttDynamicSecurityService.provisionDevice(
                savedDevice.getDeviceCode(),
                savedDevice.getMqttClientId(),
                mqttPassword
        );
        return provisioningResponse(savedDevice, mqttPassword);
    }

    @Transactional
    public DeviceProvisioningResponse rotateMqttCredential(Long id) {
        Device device = findDevice(id);
        String mqttPassword = deviceCredentialService.issueMqttPassword();
        mqttDynamicSecurityService.provisionDevice(
                device.getDeviceCode(),
                device.getMqttClientId(),
                mqttPassword
        );
        return provisioningResponse(device, mqttPassword);
    }

    @Transactional
    public DeviceResponse update(Long id, DeviceRequest request) {
        Device device = findDevice(id);
        String deviceCode = normalizeCode(request.deviceCode());
        String mqttClientId = request.mqttClientId().trim();
        if (!device.getDeviceCode().equals(deviceCode) || !device.getMqttClientId().equals(mqttClientId)) {
            throw new DeviceConflictException("Device code and MQTT client ID cannot change after provisioning");
        }

        device.updateDetails(
                device.getDeviceCode(),
                request.deviceName().trim(),
                request.deviceType(),
                request.ipAddress().trim(),
                device.getMqttClientId()
        );
        return toResponse(device);
    }

    @Transactional
    public DeviceResponse updateStatus(Long id, DeviceStatus status) {
        Device device = findDevice(id);
        device.changeStatus(status);
        return toResponse(device);
    }

    @Transactional
    public DeviceResponse transferOwnership(
            Long id,
            String ownerUsername,
            Long changedByUserId,
            String changedByUsername
    ) {
        Device device = findDevice(id);
        String normalizedUsername = ownerUsername.trim().toLowerCase(Locale.ROOT);
        UserAccount owner = userRepository.findByUsername(normalizedUsername)
                .orElseThrow(UserNotFoundException::new);
        if (owner.getRole() != UserRole.USER || !owner.isEnabled()) {
            throw new InvalidDeviceOwnerException();
        }

        UserAccount previousOwner = device.getOwner();
        if (Objects.equals(previousOwner.getId(), owner.getId())) {
            return toResponse(device);
        }

        device.changeOwner(owner);
        deviceOwnershipAuditService.recordTransfer(
                device,
                previousOwner,
                owner,
                changedByUserId,
                changedByUsername
        );
        return toResponse(device);
    }

    @Transactional
    public void revoke(Long id) {
        Device device = findDevice(id);
        device.changeStatus(DeviceStatus.REVOKED);
    }

    private Device findDevice(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));
    }

    private void ensureUnique(String deviceCode, String mqttClientId) {
        if (deviceRepository.existsByDeviceCode(deviceCode)
                || deviceRepository.existsByMqttClientId(mqttClientId)) {
            throw new DeviceConflictException();
        }
    }

    private String normalizeCode(String deviceCode) {
        return deviceCode.trim().toUpperCase(Locale.ROOT);
    }

    private DeviceProvisioningResponse provisioningResponse(Device device, String mqttPassword) {
        return new DeviceProvisioningResponse(toResponse(device), device.getDeviceCode(), mqttPassword);
    }

    private DeviceResponse toResponse(Device device) {
        return new DeviceResponse(
                device.getId(),
                device.getDeviceCode(),
                device.getDeviceName(),
                device.getDeviceType(),
                device.getIpAddress(),
                device.getMqttClientId(),
                device.getStatus(),
                device.getOwner().getId(),
                device.getOwner().getUsername(),
                device.getCreatedAt(),
                device.getLastSeenAt()
        );
    }
}
