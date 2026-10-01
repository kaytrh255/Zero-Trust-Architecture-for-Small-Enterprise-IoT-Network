package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.DeviceCredentialAuditResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceCredentialAudit;
import com.yak.zerotrust.entity.DeviceCredentialOperation;
import com.yak.zerotrust.repository.DeviceCredentialAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DeviceCredentialAuditService {

    private final DeviceCredentialAuditRepository auditRepository;

    public DeviceCredentialAuditService(DeviceCredentialAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional
    public DeviceCredentialAudit recordProvisioning(
            Device device,
            String newSigningKeyFingerprint,
            Long changedByUserId,
            String changedByUsername
    ) {
        return auditRepository.saveAndFlush(new DeviceCredentialAudit(
                device,
                DeviceCredentialOperation.PROVISION,
                null,
                newSigningKeyFingerprint,
                changedByUserId,
                changedByUsername
        ));
    }

    @Transactional
    public DeviceCredentialAudit recordRotation(
            Device device,
            String previousSigningKeyFingerprint,
            String newSigningKeyFingerprint,
            Long changedByUserId,
            String changedByUsername
    ) {
        return auditRepository.saveAndFlush(new DeviceCredentialAudit(
                device,
                DeviceCredentialOperation.ROTATE,
                previousSigningKeyFingerprint,
                newSigningKeyFingerprint,
                changedByUserId,
                changedByUsername
        ));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<DeviceCredentialAuditResponse> searchForDevice(
            Long deviceId,
            int page,
            int size,
            Instant from,
            Instant to,
            DeviceCredentialOperation operation,
            String changedByUsername
    ) {
        Specification<DeviceCredentialAudit> specification = AuditQuerySupport
                .<DeviceCredentialAudit>timestampRange("changedAt", from, to)
                .and(AuditQuerySupport.<DeviceCredentialAudit>equal("deviceId", deviceId))
                .and(AuditQuerySupport.<DeviceCredentialAudit>equal("operation", operation))
                .and(AuditQuerySupport.<DeviceCredentialAudit>equalIgnoreCase(
                        "changedByUsername", changedByUsername
                ));
        Page<DeviceCredentialAudit> audits = auditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "changedAt")
        );
        return AuditPageResponse.from(audits.map(this::toResponse));
    }

    private DeviceCredentialAuditResponse toResponse(DeviceCredentialAudit audit) {
        return new DeviceCredentialAuditResponse(
                audit.getId(),
                audit.getDeviceId(),
                audit.getDeviceCode(),
                audit.getOperation(),
                audit.getPreviousSigningKeyFingerprint(),
                audit.getNewSigningKeyFingerprint(),
                audit.getChangedByUserId(),
                audit.getChangedByUsername(),
                audit.getChangedAt()
        );
    }
}
