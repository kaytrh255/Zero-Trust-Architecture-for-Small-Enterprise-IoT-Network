package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.DeviceCredentialAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeviceCredentialAuditRepository extends JpaRepository<DeviceCredentialAudit, Long>,
        JpaSpecificationExecutor<DeviceCredentialAudit> {
}
