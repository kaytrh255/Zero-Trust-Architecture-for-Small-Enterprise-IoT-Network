package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.AccessAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccessAuditRepository extends JpaRepository<AccessAudit, Long>, JpaSpecificationExecutor<AccessAudit> {
}
