package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.DeviceOwnershipAuditResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceOwnershipAudit;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.repository.DeviceOwnershipAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    public AuditPageResponse<DeviceOwnershipAuditResponse> searchForDevice(
            Long deviceId,
            int page,
            int size,
            Instant from,
            Instant to,
            String changedByUsername,
            String newOwnerUsername
    ) {
        Specification<DeviceOwnershipAudit> specification = AuditQuerySupport
                .<DeviceOwnershipAudit>timestampRange("changedAt", from, to)
                .and(AuditQuerySupport.<DeviceOwnershipAudit>equal("deviceId", deviceId))
                .and(AuditQuerySupport.<DeviceOwnershipAudit>equalIgnoreCase("changedByUsername", changedByUsername))
                .and(AuditQuerySupport.<DeviceOwnershipAudit>equalIgnoreCase("newOwnerUsername", newOwnerUsername));
        Page<DeviceOwnershipAudit> audits = ownershipAuditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "changedAt")
        );
        return AuditPageResponse.from(audits.map(this::toResponse));
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
