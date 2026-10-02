package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.DeviceStatusAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeviceStatusAuditRepository extends JpaRepository<DeviceStatusAudit, Long>, JpaSpecificationExecutor<DeviceStatusAudit> {
}
