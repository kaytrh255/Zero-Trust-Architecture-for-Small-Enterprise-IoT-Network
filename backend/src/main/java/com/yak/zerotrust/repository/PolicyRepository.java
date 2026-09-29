package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRepository extends JpaRepository<Policy, Long> {

    List<Policy> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<Policy> findAllByEnabledTrueAndSubjectAndResourceAndActionOrderByIdAsc(
            String subject,
            String resource,
            PolicyAction action
    );
}
