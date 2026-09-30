package com.yak.zerotrust.service;

import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.dto.AccessAuditResponse;
import com.yak.zerotrust.entity.AccessAudit;
import com.yak.zerotrust.repository.AccessAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    public List<AccessAuditResponse> getRecent() {
        return accessAuditRepository.findTop100ByOrderByEvaluatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
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
