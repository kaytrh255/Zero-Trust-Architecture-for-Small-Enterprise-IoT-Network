package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.PolicyRequest;
import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyChangeOperation;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.entity.PolicySnapshot;
import com.yak.zerotrust.exception.PolicyConflictException;
import com.yak.zerotrust.exception.PolicyNotFoundException;
import com.yak.zerotrust.repository.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
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

    @Mock
    private PolicyChangeAuditService policyChangeAuditService;

    @InjectMocks
    private PolicyService policyService;

    @Test
    void createTrimsAndNormalizesPolicyFieldsAndRecordsTheAfterSnapshot() {
        when(policyRepository.existsByName("Sensor Read Data Extra")).thenReturn(false);
        when(policyRepository.save(any(Policy.class))).thenAnswer(invocation -> {
            Policy policy = invocation.getArgument(0);
            setEntityId(policy, 17L);
            return policy;
        });

        var response = policyService.create(request(
                " Sensor Read Data Extra ",
                " sensor ",
                " SENSOR-Data ",
                PolicyAction.READ,
                PolicyEffect.ALLOW,
                true,
                "  Example rule  "
        ), 7L, "admin");

        PolicySnapshot expectedSnapshot = new PolicySnapshot(
                "Sensor Read Data Extra",
                "SENSOR",
                "sensor-data",
                PolicyAction.READ,
                PolicyEffect.ALLOW,
                true,
                "Example rule"
        );
        assertThat(response.name()).isEqualTo("Sensor Read Data Extra");
        assertThat(response.effect()).isEqualTo(PolicyEffect.ALLOW);
        verify(policyChangeAuditService).recordChange(
                17L,
                PolicyChangeOperation.CREATE,
                null,
                expectedSnapshot,
                7L,
                "admin"
        );
    }

    @Test
    void createRejectsDuplicatePolicyNamesWithoutRecordingAnAudit() {
        when(policyRepository.existsByName("Sensor Read Data")).thenReturn(true);

        assertThatThrownBy(() -> policyService.create(request(
                "Sensor Read Data",
                "SENSOR",
                "sensor-data",
                PolicyAction.READ,
                PolicyEffect.ALLOW,
                true,
                null
        ), 7L, "admin")).isInstanceOf(PolicyConflictException.class);

        verify(policyRepository, never()).save(any(Policy.class));
        verify(policyChangeAuditService, never()).recordChange(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateRecordsCompleteBeforeAndAfterSnapshots() {
        Policy policy = policy();
        setEntityId(policy, 4L);
        when(policyRepository.findById(4L)).thenReturn(Optional.of(policy));
        when(policyRepository.existsByNameAndIdNot("Updated Sensor Rule", 4L)).thenReturn(false);

        policyService.update(4L, request(
                " Updated Sensor Rule ",
                " camera ",
                " video-stream ",
                PolicyAction.WRITE,
                PolicyEffect.DENY,
                false,
                " changed description "
        ), 7L, "admin");

        PolicySnapshot before = new PolicySnapshot(
                "Sensor Rule", "SENSOR", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW, true, "original"
        );
        PolicySnapshot after = new PolicySnapshot(
                "Updated Sensor Rule", "CAMERA", "video-stream", PolicyAction.WRITE, PolicyEffect.DENY,
                false, "changed description"
        );
        verify(policyRepository).flush();
        verify(policyChangeAuditService).recordChange(
                4L,
                PolicyChangeOperation.UPDATE,
                before,
                after,
                7L,
                "admin"
        );
    }

    @Test
    void identicalPolicyUpdateIsANoOpAndDoesNotCreateAnAudit() {
        Policy policy = policy();
        setEntityId(policy, 4L);
        when(policyRepository.findById(4L)).thenReturn(Optional.of(policy));
        when(policyRepository.existsByNameAndIdNot("Sensor Rule", 4L)).thenReturn(false);

        policyService.update(4L, request(
                "Sensor Rule", "SENSOR", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW, true, "original"
        ), 7L, "admin");

        verify(policyRepository, never()).flush();
        verify(policyChangeAuditService, never()).recordChange(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteRecordsTheLastSnapshotBeforeRemovingThePolicy() {
        Policy policy = policy();
        setEntityId(policy, 4L);
        when(policyRepository.findById(4L)).thenReturn(Optional.of(policy));

        policyService.delete(4L, 7L, "admin");

        verify(policyChangeAuditService).recordChange(
                4L,
                PolicyChangeOperation.DELETE,
                new PolicySnapshot("Sensor Rule", "SENSOR", "sensor-data", PolicyAction.READ,
                        PolicyEffect.ALLOW, true, "original"),
                null,
                7L,
                "admin"
        );
        verify(policyRepository).delete(policy);
    }

    @Test
    void getByIdThrowsWhenPolicyDoesNotExist() {
        when(policyRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.getById(42L))
                .isInstanceOf(PolicyNotFoundException.class);
    }

    private Policy policy() {
        return new Policy(
                "Sensor Rule",
                "SENSOR",
                "sensor-data",
                PolicyAction.READ,
                PolicyEffect.ALLOW,
                true,
                "original"
        );
    }

    private PolicyRequest request(
            String name,
            String subject,
            String resource,
            PolicyAction action,
            PolicyEffect effect,
            boolean enabled,
            String description
    ) {
        return new PolicyRequest(name, subject, resource, action, effect, enabled, description);
    }

    private void setEntityId(Object entity, Long id) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to prepare persisted test entity", exception);
        }
    }
}
