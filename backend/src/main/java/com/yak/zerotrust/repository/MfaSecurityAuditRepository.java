package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.MfaSecurityAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MfaSecurityAuditRepository extends JpaRepository<MfaSecurityAudit, Long>,
        JpaSpecificationExecutor<MfaSecurityAudit> {
}
