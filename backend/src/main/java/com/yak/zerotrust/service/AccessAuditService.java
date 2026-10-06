package com.yak.zerotrust.service;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.dto.AccessAuditResponse;
import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.entity.AccessAudit;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.repository.AccessAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AccessAuditService {

    private final AccessAuditRepository accessAuditRepository;

    public AccessAuditService(AccessAuditRepository accessAuditRepository) {
        this.accessAuditRepository = accessAuditRepository;
    }

    @Transactional
    public AccessAudit record(AccessContext context, AccessDecision decision) {
        return accessAuditRepository.saveAndFlush(new AccessAudit(context, decision));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<AccessAuditResponse> search(
            int page,
            int size,
            Instant from,
            Instant to,
            AccessDecisionOutcome decision,
            AccessDecisionReason reason,
            AccessChannel channel,
            String deviceCode,
            String requesterUsername,
            String resource,
            PolicyAction action
    ) {
        Specification<AccessAudit> specification = AuditQuerySupport.<AccessAudit>timestampRange(
                        "evaluatedAt", from, to
                )
                .and(AuditQuerySupport.<AccessAudit>equal("decision", decision))
                .and(AuditQuerySupport.<AccessAudit>equal("reason", reason))
                .and(AuditQuerySupport.<AccessAudit>equal("channel", channel))
                .and(AuditQuerySupport.<AccessAudit>equal("action", action))
                .and(AuditQuerySupport.<AccessAudit>equalIgnoreCase("deviceCode", deviceCode))
                .and(AuditQuerySupport.<AccessAudit>equalIgnoreCase("requesterUsername", requesterUsername))
                .and(AuditQuerySupport.<AccessAudit>equalIgnoreCase("resource", resource));
        Page<AccessAudit> audits = accessAuditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "evaluatedAt")
        );
        return AuditPageResponse.from(audits.map(this::toResponse));
    }

    private AccessAuditResponse toResponse(AccessAudit audit) {
        return new AccessAuditResponse(
                audit.getId(),
                audit.getRequesterId(),
                audit.getRequesterUsername(),
                audit.getRequesterRole(),
                audit.getChannel(),
                audit.getDeviceId(),
                audit.getDeviceCode(),
                audit.getDeviceType(),
                audit.getDeviceStatus(),
                audit.getResource(),
                audit.getAction(),
                audit.getDecision(),
                audit.getReason(),
                audit.getMessageSequence(),
                audit.getMatchedPolicyId(),
                audit.getMatchedPolicyName(),
                audit.getEvaluatedAt()
        );
    }
}
