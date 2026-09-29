package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.AccessAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessAuditRepository extends JpaRepository<AccessAudit, Long> {

    List<AccessAudit> findTop100ByOrderByEvaluatedAtDesc();
}
