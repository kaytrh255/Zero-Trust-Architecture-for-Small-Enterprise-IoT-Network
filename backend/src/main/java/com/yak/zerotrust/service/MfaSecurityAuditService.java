package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.MfaSecurityAuditResponse;
import com.yak.zerotrust.entity.MfaSecurityAudit;
import com.yak.zerotrust.entity.MfaSecurityAuditOperation;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.repository.MfaSecurityAuditRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MfaSecurityAuditService {

    private final MfaSecurityAuditRepository repository;

    public MfaSecurityAuditService(MfaSecurityAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(UserAccount user, MfaSecurityAuditOperation operation) {
        record(user, operation, user);
    }

    @Transactional
    public void record(UserAccount target, MfaSecurityAuditOperation operation, UserAccount actor) {
        repository.saveAndFlush(new MfaSecurityAudit(
                target.getId(),
                target.getUsername(),
                operation,
                actor.getId(),
                actor.getUsername()
        ));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<MfaSecurityAuditResponse> search(
            int page,
            int size,
            Instant from,
            Instant to,
            MfaSecurityAuditOperation operation,
            String username
    ) {
        Specification<MfaSecurityAudit> specification = AuditQuerySupport
                .<MfaSecurityAudit>timestampRange("changedAt", from, to)
                .and(AuditQuerySupport.<MfaSecurityAudit>equal("operation", operation))
                .and(AuditQuerySupport.<MfaSecurityAudit>equalIgnoreCase("username", username));
        Page<MfaSecurityAudit> audits = repository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "changedAt")
        );
        return AuditPageResponse.from(audits.map(audit -> new MfaSecurityAuditResponse(
                audit.getId(),
                audit.getUserId(),
                audit.getUsername(),
                audit.getOperation(),
                audit.getChangedAt(),
                audit.getActorUserId(),
                audit.getActorUsername()
        )));
    }
}
