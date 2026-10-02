package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.PolicyChangeAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PolicyChangeAuditRepository extends JpaRepository<PolicyChangeAudit, Long>, JpaSpecificationExecutor<PolicyChangeAudit> {
}
