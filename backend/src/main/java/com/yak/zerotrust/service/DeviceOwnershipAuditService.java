package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.DeviceOwnershipAuditResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceOwnershipAudit;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.repository.DeviceOwnershipAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeviceOwnershipAuditService {

    private final DeviceOwnershipAuditRepository ownershipAuditRepository;

    public DeviceOwnershipAuditService(DeviceOwnershipAuditRepository ownershipAuditRepository) {
        this.ownershipAuditRepository = ownershipAuditRepository;
    }

    @Transactional
    public DeviceOwnershipAudit recordTransfer(
            Device device,
            UserAccount previousOwner,
            UserAccount newOwner,
            Long changedByUserId,
            String changedByUsername
    ) {
        return ownershipAuditRepository.saveAndFlush(new DeviceOwnershipAudit(
                device,
                previousOwner,
                newOwner,
                changedByUserId,
                changedByUsername
        ));
    }

    @Transactional(readOnly = true)
    public List<DeviceOwnershipAuditResponse> getRecentForDevice(Long deviceId) {
        return ownershipAuditRepository.findTop100ByDeviceIdOrderByChangedAtDescIdDesc(deviceId).stream()
                .map(this::toResponse)
                .toList();
    }

    private DeviceOwnershipAuditResponse toResponse(DeviceOwnershipAudit audit) {
        return new DeviceOwnershipAuditResponse(
                audit.getId(),
                audit.getDeviceId(),
                audit.getDeviceCode(),
                audit.getPreviousOwnerId(),
                audit.getPreviousOwnerUsername(),
                audit.getNewOwnerId(),
                audit.getNewOwnerUsername(),
                audit.getChangedByUserId(),
                audit.getChangedByUsername(),
                audit.getChangedAt()
        );
    }
}
