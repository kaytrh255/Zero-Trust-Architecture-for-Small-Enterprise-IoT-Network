package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.DeviceProvisioningResponse;
import com.yak.zerotrust.dto.DeviceRequest;
import com.yak.zerotrust.dto.DeviceResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.exception.DeviceConflictException;
import com.yak.zerotrust.exception.DeviceNotFoundException;
import com.yak.zerotrust.exception.UserNotFoundException;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final DeviceCredentialService deviceCredentialService;

    public DeviceService(
            DeviceRepository deviceRepository,
            UserRepository userRepository,
            DeviceCredentialService deviceCredentialService
    ) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.deviceCredentialService = deviceCredentialService;
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

    @Transactional
    public DeviceProvisioningResponse create(DeviceRequest request, Long ownerId) {
        String deviceCode = normalizeCode(request.deviceCode());
        String mqttClientId = request.mqttClientId().trim();
        ensureUnique(deviceCode, mqttClientId, null);

        UserAccount owner = userRepository.findById(ownerId)
                .orElseThrow(UserNotFoundException::new);
        DeviceCredentialService.IssuedCredential credential = deviceCredentialService.issue();
        Device device = new Device(
                deviceCode,
                request.deviceName().trim(),
                request.deviceType(),
                request.ipAddress().trim(),
                mqttClientId,
                owner
        );
        device.replaceMqttCredentialHash(credential.passwordHash());
        Device savedDevice = deviceRepository.save(device);
        return new DeviceProvisioningResponse(toResponse(savedDevice), credential.token());
    }

    @Transactional
    public DeviceProvisioningResponse rotateMqttCredential(Long id) {
        Device device = findDevice(id);
        DeviceCredentialService.IssuedCredential credential = deviceCredentialService.issue();
        device.replaceMqttCredentialHash(credential.passwordHash());
        return new DeviceProvisioningResponse(toResponse(device), credential.token());
    }

    @Transactional
    public DeviceResponse update(Long id, DeviceRequest request) {
        Device device = findDevice(id);
        String deviceCode = normalizeCode(request.deviceCode());
        String mqttClientId = request.mqttClientId().trim();
        ensureUnique(deviceCode, mqttClientId, id);

        device.updateDetails(
                deviceCode,
                request.deviceName().trim(),
                request.deviceType(),
                request.ipAddress().trim(),
                mqttClientId
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
    public void revoke(Long id) {
        Device device = findDevice(id);
        device.changeStatus(DeviceStatus.REVOKED);
    }

    private Device findDevice(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));
    }

    private void ensureUnique(String deviceCode, String mqttClientId, Long currentId) {
        boolean codeExists = currentId == null
                ? deviceRepository.existsByDeviceCode(deviceCode)
                : deviceRepository.existsByDeviceCodeAndIdNot(deviceCode, currentId);
        boolean mqttClientExists = currentId == null
                ? deviceRepository.existsByMqttClientId(mqttClientId)
                : deviceRepository.existsByMqttClientIdAndIdNot(mqttClientId, currentId);

        if (codeExists || mqttClientExists) {
            throw new DeviceConflictException();
        }
    }

    private String normalizeCode(String deviceCode) {
        return deviceCode.trim().toUpperCase(Locale.ROOT);
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
