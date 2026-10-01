package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.DeviceOwnershipAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceOwnershipAuditRepository extends JpaRepository<DeviceOwnershipAudit, Long> {

    List<DeviceOwnershipAudit> findTop100ByDeviceIdOrderByChangedAtDescIdDesc(Long deviceId);
}
