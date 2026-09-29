package com.yak.zerotrust.policy;

import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.repository.PolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PolicyEvaluationService {

    private final PolicyRepository policyRepository;

    public PolicyEvaluationService(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Policy> findApplicablePolicy(String subject, String resource, PolicyAction action) {
        List<Policy> matches = policyRepository
                .findAllByEnabledTrueAndSubjectAndResourceAndActionOrderByIdAsc(
                        subject.trim().toUpperCase(Locale.ROOT),
                        resource.trim().toLowerCase(Locale.ROOT),
                        action
                );

        return matches.stream()
                .filter(policy -> policy.getEffect() == PolicyEffect.DENY)
                .findFirst()
                .or(() -> matches.stream()
                        .filter(policy -> policy.getEffect() == PolicyEffect.ALLOW)
                        .findFirst());
    }
}
