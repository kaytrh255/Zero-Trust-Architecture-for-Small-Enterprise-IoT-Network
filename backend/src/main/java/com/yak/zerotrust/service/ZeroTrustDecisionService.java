package com.yak.zerotrust.service;

import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.entity.AccessAudit;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.policy.PolicyEvaluationService;
import com.yak.zerotrust.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class ZeroTrustDecisionService {

    private final DeviceRepository deviceRepository;
    private final PolicyEvaluationService policyEvaluationService;
    private final AccessAuditService accessAuditService;

    public ZeroTrustDecisionService(
            DeviceRepository deviceRepository,
            PolicyEvaluationService policyEvaluationService,
            AccessAuditService accessAuditService
    ) {
        this.deviceRepository = deviceRepository;
        this.policyEvaluationService = policyEvaluationService;
        this.accessAuditService = accessAuditService;
    }

    @Transactional
    public AccessEvaluation evaluate(AccessContext requestedContext) {
        AccessContext normalizedContext = normalizeContext(requestedContext);
        Optional<Device> registeredDevice = isRequesterRoleAllowed(normalizedContext)
                ? deviceRepository.findByDeviceCodeForUpdate(normalizedContext.deviceCode())
                : Optional.empty();
        AccessContext context = registeredDevice
                .map(normalizedContext::withDevice)
                .orElse(normalizedContext);
        AccessDecision decision = decide(context);

        AccessAudit savedAudit = accessAuditService.record(context, decision);
        return new AccessEvaluation(decision.withAuditId(savedAudit.getId()), registeredDevice.orElse(null));
    }

    private AccessDecision decide(AccessContext context) {
        Instant evaluatedAt = Instant.now();
        if (!isRequesterRoleAllowed(context)) {
            return deny(context, AccessDecisionReason.REQUESTER_ROLE_NOT_ALLOWED, null, evaluatedAt);
        }
        if (context.deviceId() == null) {
            return deny(context, AccessDecisionReason.DEVICE_NOT_FOUND, null, evaluatedAt);
        }
        if (context.deviceStatus() != DeviceStatus.ACTIVE) {
            return deny(context, AccessDecisionReason.DEVICE_NOT_ACTIVE, null, evaluatedAt);
        }

        Optional<Policy> applicablePolicy = policyEvaluationService.findApplicablePolicy(
                context.deviceType().name(),
                context.resource(),
                context.action()
        );
        if (applicablePolicy.isEmpty()) {
            return deny(context, AccessDecisionReason.NO_MATCHING_POLICY, null, evaluatedAt);
        }

        Policy policy = applicablePolicy.get();
        if (policy.getEffect() == PolicyEffect.DENY) {
            return deny(context, AccessDecisionReason.EXPLICIT_DENY, policy, evaluatedAt);
        }
        return new AccessDecision(
                null,
                AccessDecisionOutcome.ALLOW,
                AccessDecisionReason.POLICY_ALLOW,
                context.deviceCode(),
                context.resource(),
                context.action(),
                policy.getId(),
                policy.getName(),
                evaluatedAt
        );
    }

    private boolean isRequesterRoleAllowed(AccessContext context) {
        return switch (context.channel()) {
            case API -> context.requesterRole() == UserRole.USER || context.requesterRole() == UserRole.DEVICE;
            case MQTT -> context.requesterRole() == UserRole.DEVICE;
        };
    }

    private AccessDecision deny(
            AccessContext context,
            AccessDecisionReason reason,
            Policy policy,
            Instant evaluatedAt
    ) {
        return new AccessDecision(
                null,
                AccessDecisionOutcome.DENY,
                reason,
                context.deviceCode(),
                context.resource(),
                context.action(),
                policy == null ? null : policy.getId(),
                policy == null ? null : policy.getName(),
                evaluatedAt
        );
    }

    private AccessContext normalizeContext(AccessContext context) {
        return new AccessContext(
                context.requesterId(),
                context.requesterUsername(),
                context.requesterRole(),
                context.channel(),
                context.deviceCode().trim().toUpperCase(Locale.ROOT),
                context.deviceId(),
                context.deviceType(),
                context.deviceStatus(),
                context.resource().trim().toLowerCase(Locale.ROOT),
                context.action()
        );
    }
}
