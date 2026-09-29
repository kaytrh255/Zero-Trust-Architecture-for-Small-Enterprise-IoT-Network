package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.PolicyRequest;
import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.exception.PolicyConflictException;
import com.yak.zerotrust.exception.PolicyNotFoundException;
import com.yak.zerotrust.repository.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    @InjectMocks
    private PolicyService policyService;

    @Test
    void createTrimsAndNormalizesPolicyFields() {
        when(policyRepository.existsByName("Sensor Read Data Extra")).thenReturn(false);
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = policyService.create(new PolicyRequest(
                " Sensor Read Data Extra ",
                " sensor ",
                " SENSOR-Data ",
                PolicyAction.READ,
                PolicyEffect.ALLOW,
                true,
                "  Example rule  "
        ));

        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(policyRepository).save(policyCaptor.capture());
        Policy savedPolicy = policyCaptor.getValue();
        assertThat(savedPolicy.getName()).isEqualTo("Sensor Read Data Extra");
        assertThat(savedPolicy.getSubject()).isEqualTo("SENSOR");
        assertThat(savedPolicy.getResource()).isEqualTo("sensor-data");
        assertThat(savedPolicy.getDescription()).isEqualTo("Example rule");
        assertThat(response.name()).isEqualTo("Sensor Read Data Extra");
        assertThat(response.effect()).isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void createRejectsDuplicatePolicyNames() {
        when(policyRepository.existsByName("Sensor Read Data")).thenReturn(true);

        assertThatThrownBy(() -> policyService.create(new PolicyRequest(
                "Sensor Read Data",
                "SENSOR",
                "sensor-data",
                PolicyAction.READ,
                PolicyEffect.ALLOW,
                true,
                null
        ))).isInstanceOf(PolicyConflictException.class);

        verify(policyRepository, never()).save(any(Policy.class));
    }

    @Test
    void getByIdThrowsWhenPolicyDoesNotExist() {
        when(policyRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.getById(42L))
                .isInstanceOf(PolicyNotFoundException.class);
    }
}
