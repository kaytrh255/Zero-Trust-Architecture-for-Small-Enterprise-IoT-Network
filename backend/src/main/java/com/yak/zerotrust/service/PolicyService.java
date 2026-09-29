package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.PolicyRequest;
import com.yak.zerotrust.dto.PolicyResponse;
import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.repository.PolicyRepository;
import com.yak.zerotrust.exception.PolicyConflictException;
import com.yak.zerotrust.exception.PolicyNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class PolicyService {

    private final PolicyRepository policyRepository;

    public PolicyService(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> getAll() {
        return policyRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyResponse getById(Long id) {
        return toResponse(findPolicy(id));
    }

    @Transactional
    public PolicyResponse create(PolicyRequest request) {
        String name = request.name().trim();
        ensureUniqueName(name, null);
        Policy policy = new Policy(
                name,
                normalizeSubject(request.subject()),
                normalizeResource(request.resource()),
                request.action(),
                request.effect(),
                request.enabled(),
                normalizeDescription(request.description())
        );
        return toResponse(policyRepository.save(policy));
    }

    @Transactional
    public PolicyResponse update(Long id, PolicyRequest request) {
        Policy policy = findPolicy(id);
        String name = request.name().trim();
        ensureUniqueName(name, id);
        policy.updateDetails(
                name,
                normalizeSubject(request.subject()),
                normalizeResource(request.resource()),
                request.action(),
                request.effect(),
                request.enabled(),
                normalizeDescription(request.description())
        );
        policyRepository.flush();
        return toResponse(policy);
    }

    @Transactional
    public void delete(Long id) {
        Policy policy = findPolicy(id);
        policyRepository.delete(policy);
    }

    private Policy findPolicy(Long id) {
        return policyRepository.findById(id)
                .orElseThrow(() -> new PolicyNotFoundException(id));
    }

    private void ensureUniqueName(String name, Long currentId) {
        boolean exists = currentId == null
                ? policyRepository.existsByName(name)
                : policyRepository.existsByNameAndIdNot(name, currentId);
        if (exists) {
            throw new PolicyConflictException();
        }
    }

    private String normalizeSubject(String subject) {
        return subject.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeResource(String resource) {
        return resource.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.trim();
    }

    private PolicyResponse toResponse(Policy policy) {
        return new PolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getSubject(),
                policy.getResource(),
                policy.getAction(),
                policy.getEffect(),
                policy.isEnabled(),
                policy.getDescription(),
                policy.getCreatedAt(),
                policy.getUpdatedAt()
        );
    }
}
