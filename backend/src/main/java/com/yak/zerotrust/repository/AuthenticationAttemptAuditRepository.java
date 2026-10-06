package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.AuthenticationAttemptAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuthenticationAttemptAuditRepository extends JpaRepository<AuthenticationAttemptAudit, Long>,
        JpaSpecificationExecutor<AuthenticationAttemptAudit> {
}
