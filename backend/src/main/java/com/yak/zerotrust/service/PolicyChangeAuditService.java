package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.PolicyChangeAuditResponse;
import com.yak.zerotrust.dto.PolicyChangeSnapshotResponse;
import com.yak.zerotrust.entity.PolicyChangeAudit;
import com.yak.zerotrust.entity.PolicyChangeOperation;
import com.yak.zerotrust.entity.PolicySnapshot;
import com.yak.zerotrust.repository.PolicyChangeAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PolicyChangeAuditService {

    private final PolicyChangeAuditRepository auditRepository;

    public PolicyChangeAuditService(PolicyChangeAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional
    public PolicyChangeAudit recordChange(
            Long policyId,
            PolicyChangeOperation operation,
            PolicySnapshot before,
            PolicySnapshot after,
            Long changedByUserId,
            String changedByUsername
    ) {
        return auditRepository.saveAndFlush(new PolicyChangeAudit(
                policyId,
                operation,
                before,
                after,
                changedByUserId,
                changedByUsername
        ));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<PolicyChangeAuditResponse> searchForPolicy(
            Long policyId,
            int page,
            int size,
            Instant from,
            Instant to,
            PolicyChangeOperation operation,
            String changedByUsername
    ) {
        Specification<PolicyChangeAudit> specification = AuditQuerySupport
                .<PolicyChangeAudit>timestampRange("changedAt", from, to)
                .and(AuditQuerySupport.<PolicyChangeAudit>equal("policyId", policyId))
                .and(AuditQuerySupport.<PolicyChangeAudit>equal("operation", operation))
                .and(AuditQuerySupport.<PolicyChangeAudit>equalIgnoreCase("changedByUsername", changedByUsername));
        Page<PolicyChangeAudit> audits = auditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "changedAt")
        );
        return AuditPageResponse.from(audits.map(this::toResponse));
    }

    private PolicyChangeAuditResponse toResponse(PolicyChangeAudit audit) {
        return new PolicyChangeAuditResponse(
                audit.getId(),
                audit.getPolicyId(),
                audit.getOperation(),
                toSnapshotResponse(audit.getBeforeSnapshot()),
                toSnapshotResponse(audit.getAfterSnapshot()),
                audit.getChangedByUserId(),
                audit.getChangedByUsername(),
                audit.getChangedAt()
        );
    }

    private PolicyChangeSnapshotResponse toSnapshotResponse(PolicySnapshot snapshot) {
        return snapshot == null ? null : new PolicyChangeSnapshotResponse(
                snapshot.name(),
                snapshot.subject(),
                snapshot.resource(),
                snapshot.action(),
                snapshot.effect(),
                snapshot.enabled(),
                snapshot.description()
        );
    }
}
