package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.UserAccountAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserAccountAuditRepository extends JpaRepository<UserAccountAudit, Long>, JpaSpecificationExecutor<UserAccountAudit> {
}
