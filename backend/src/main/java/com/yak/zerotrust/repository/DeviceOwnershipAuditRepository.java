package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.DeviceOwnershipAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeviceOwnershipAuditRepository extends JpaRepository<DeviceOwnershipAudit, Long>, JpaSpecificationExecutor<DeviceOwnershipAudit> {
}
