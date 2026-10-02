package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.AuthenticationAttemptAuditResponse;
import com.yak.zerotrust.entity.AuthenticationAttemptAudit;
import com.yak.zerotrust.entity.AuthenticationAttemptOutcome;
import com.yak.zerotrust.repository.AuthenticationAttemptAuditRepository;
import com.yak.zerotrust.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthenticationAuditService {

    private final AuthenticationAttemptAuditRepository auditRepository;

    public AuthenticationAuditService(AuthenticationAttemptAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional
    public void recordSuccessfulLogin(UserPrincipal principal) {
        auditRepository.saveAndFlush(new AuthenticationAttemptAudit(
                principal.getUsername(),
                principal.getId(),
                AuthenticationAttemptOutcome.SUCCESS
        ));
    }

    @Transactional
    public void recordFailedLogin(String normalizedUsername) {
        auditRepository.saveAndFlush(new AuthenticationAttemptAudit(
                normalizedUsername,
                null,
                AuthenticationAttemptOutcome.FAILURE
        ));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse<AuthenticationAttemptAuditResponse> search(
            int page,
            int size,
            Instant from,
            Instant to,
            AuthenticationAttemptOutcome outcome,
            String username
    ) {
        Specification<AuthenticationAttemptAudit> specification = AuditQuerySupport
                .<AuthenticationAttemptAudit>timestampRange("attemptedAt", from, to)
                .and(AuditQuerySupport.<AuthenticationAttemptAudit>equal("outcome", outcome))
                .and(AuditQuerySupport.<AuthenticationAttemptAudit>equalIgnoreCase("attemptedUsername", username));
        Page<AuthenticationAttemptAudit> audits = auditRepository.findAll(
                specification,
                AuditQuerySupport.pageable(page, size, from, to, "attemptedAt")
        );
        return AuditPageResponse.from(audits.map(this::toResponse));
    }

    private AuthenticationAttemptAuditResponse toResponse(AuthenticationAttemptAudit audit) {
        return new AuthenticationAttemptAuditResponse(
                audit.getId(),
                audit.getAttemptedUsername(),
                audit.getAuthenticatedUserId(),
                audit.getOutcome(),
                audit.getAttemptedAt()
        );
    }
}
