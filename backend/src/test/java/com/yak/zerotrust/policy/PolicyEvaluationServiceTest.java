package com.yak.zerotrust.policy;

import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.repository.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyEvaluationServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    @InjectMocks
    private PolicyEvaluationService policyEvaluationService;

    @Test
    void explicitDenyWinsOverMatchingAllow() {
        Policy allow = policy("Allow sensor write", "camera-stream", PolicyAction.WRITE, PolicyEffect.ALLOW);
        Policy deny = policy("Deny sensor write", "camera-stream", PolicyAction.WRITE, PolicyEffect.DENY);
        when(policyRepository.findAllByEnabledTrueAndSubjectAndResourceAndActionOrderByIdAsc(
                "SENSOR", "camera-stream", PolicyAction.WRITE
        )).thenReturn(List.of(allow, deny));

        assertThat(policyEvaluationService.findApplicablePolicy("sensor", "CAMERA-STREAM", PolicyAction.WRITE))
                .contains(deny);
    }

    @Test
    void returnsMatchingAllowWhenThereIsNoExplicitDeny() {
        Policy allow = policy("Sensor read", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW);
        when(policyRepository.findAllByEnabledTrueAndSubjectAndResourceAndActionOrderByIdAsc(
                "SENSOR", "sensor-data", PolicyAction.READ
        )).thenReturn(List.of(allow));

        assertThat(policyEvaluationService.findApplicablePolicy("SENSOR", "sensor-data", PolicyAction.READ))
                .contains(allow);
    }

    @Test
    void returnsEmptyWhenNoEnabledPolicyMatches() {
        when(policyRepository.findAllByEnabledTrueAndSubjectAndResourceAndActionOrderByIdAsc(
                "SENSOR", "unknown-resource", PolicyAction.READ
        )).thenReturn(List.of());

        assertThat(policyEvaluationService.findApplicablePolicy("sensor", "unknown-resource", PolicyAction.READ))
                .isEmpty();
        verify(policyRepository).findAllByEnabledTrueAndSubjectAndResourceAndActionOrderByIdAsc(
                "SENSOR", "unknown-resource", PolicyAction.READ
        );
    }

    private Policy policy(String name, String resource, PolicyAction action, PolicyEffect effect) {
        return new Policy(name, "SENSOR", resource, action, effect, true, null);
    }
}
