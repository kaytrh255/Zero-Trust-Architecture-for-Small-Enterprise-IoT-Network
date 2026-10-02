package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.DeviceStatusAuditResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceStatusAudit;
import com.yak.zerotrust.repository.DeviceStatusAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DeviceStatusAuditService {

    private final DeviceStatusAuditRepository auditRepository;

    public DeviceStatusAuditService(DeviceStatusAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional
    public DeviceStatusAudit recordChange(
            Device device,
            DeviceStatus previousStatus,
            DeviceStatus newStatus,
            Long changedByUserId,
            String changedByUsername
    ) {
        return auditRepository.saveAndFlush(new DeviceStatusAudit(
                device,
                previousStatus,
                newStatus,
                changedByUserId,
                changedByUsername
        ));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<DeviceStatusAuditResponse> searchForDevice(
            Long deviceId,
            int page,
            int size,
            Instant from,
            Instant to,
            DeviceStatus newStatus,
            String changedByUsername
    ) {
        Specification<DeviceStatusAudit> specification = AuditQuerySupport
                .<DeviceStatusAudit>timestampRange("changedAt", from, to)
                .and(AuditQuerySupport.<DeviceStatusAudit>equal("deviceId", deviceId))
                .and(AuditQuerySupport.<DeviceStatusAudit>equal("newStatus", newStatus))
                .and(AuditQuerySupport.<DeviceStatusAudit>equalIgnoreCase("changedByUsername", changedByUsername));
        Page<DeviceStatusAudit> audits = auditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "changedAt")
        );
        return AuditPageResponse.from(audits.map(this::toResponse));
    }

    private DeviceStatusAuditResponse toResponse(DeviceStatusAudit audit) {
        return new DeviceStatusAuditResponse(
                audit.getId(),
                audit.getDeviceId(),
                audit.getDeviceCode(),
                audit.getPreviousStatus(),
                audit.getNewStatus(),
                audit.getChangedByUserId(),
                audit.getChangedByUsername(),
                audit.getChangedAt()
        );
    }
}
