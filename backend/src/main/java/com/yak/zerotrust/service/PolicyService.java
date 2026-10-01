package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.PolicyChangeAuditResponse;
import com.yak.zerotrust.dto.PolicyRequest;
import com.yak.zerotrust.dto.PolicyResponse;
import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyChangeOperation;
import com.yak.zerotrust.entity.PolicySnapshot;
import com.yak.zerotrust.exception.PolicyConflictException;
import com.yak.zerotrust.exception.PolicyNotFoundException;
import com.yak.zerotrust.repository.PolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final PolicyChangeAuditService policyChangeAuditService;

    public PolicyService(
            PolicyRepository policyRepository,
            PolicyChangeAuditService policyChangeAuditService
    ) {
        this.policyRepository = policyRepository;
        this.policyChangeAuditService = policyChangeAuditService;
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

    @Transactional(readOnly = true)
    public AuditPageResponse<PolicyChangeAuditResponse> getAudits(
            Long id,
            int page,
            int size,
            Instant from,
            Instant to,
            PolicyChangeOperation operation,
            String changedByUsername
    ) {
        return policyChangeAuditService.searchForPolicy(
                id, page, size, from, to, operation, changedByUsername
        );
    }

    @Transactional
    public PolicyResponse create(PolicyRequest request, Long changedByUserId, String changedByUsername) {
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
        Policy savedPolicy = policyRepository.save(policy);
        policyChangeAuditService.recordChange(
                savedPolicy.getId(),
                PolicyChangeOperation.CREATE,
                null,
                PolicySnapshot.from(savedPolicy),
                changedByUserId,
                changedByUsername
        );
        return toResponse(savedPolicy);
    }

    @Transactional
    public PolicyResponse update(
            Long id,
            PolicyRequest request,
            Long changedByUserId,
            String changedByUsername
    ) {
        Policy policy = findPolicy(id);
        String name = request.name().trim();
        String subject = normalizeSubject(request.subject());
        String resource = normalizeResource(request.resource());
        String description = normalizeDescription(request.description());
        ensureUniqueName(name, id);

        PolicySnapshot before = PolicySnapshot.from(policy);
        PolicySnapshot after = new PolicySnapshot(
                name,
                subject,
                resource,
                request.action(),
                request.effect(),
                request.enabled(),
                description
        );
        if (before.equals(after)) {
            return toResponse(policy);
        }

        policy.updateDetails(
                name,
                subject,
                resource,
                request.action(),
                request.effect(),
                request.enabled(),
                description
        );
        policyRepository.flush();
        policyChangeAuditService.recordChange(
                id,
                PolicyChangeOperation.UPDATE,
                before,
                PolicySnapshot.from(policy),
                changedByUserId,
                changedByUsername
        );
        return toResponse(policy);
    }

    @Transactional
    public void delete(Long id, Long changedByUserId, String changedByUsername) {
        Policy policy = findPolicy(id);
        policyChangeAuditService.recordChange(
                id,
                PolicyChangeOperation.DELETE,
                PolicySnapshot.from(policy),
                null,
                changedByUserId,
                changedByUsername
        );
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
