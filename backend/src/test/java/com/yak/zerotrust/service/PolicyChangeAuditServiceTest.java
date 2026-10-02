package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.PolicyChangeAuditResponse;
import com.yak.zerotrust.dto.PolicyChangeSnapshotResponse;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyChangeAudit;
import com.yak.zerotrust.entity.PolicyChangeOperation;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.entity.PolicySnapshot;
import com.yak.zerotrust.repository.PolicyChangeAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyChangeAuditServiceTest {

    @Mock
    private PolicyChangeAuditRepository auditRepository;

    @Test
    void persistsAndReturnsPolicySnapshotsForAnUpdate() {
        PolicySnapshot before = new PolicySnapshot(
                "Sensor Rule", "SENSOR", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW, true, "old"
        );
        PolicySnapshot after = new PolicySnapshot(
                "Camera Rule", "CAMERA", "video-stream", PolicyAction.WRITE, PolicyEffect.DENY, false, "new"
        );
        when(auditRepository.saveAndFlush(any(PolicyChangeAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        PolicyChangeAuditService service = new PolicyChangeAuditService(auditRepository);

        PolicyChangeAudit saved = service.recordChange(
                44L,
                PolicyChangeOperation.UPDATE,
                before,
                after,
                7L,
                "admin"
        );

        ArgumentCaptor<PolicyChangeAudit> auditCaptor = ArgumentCaptor.forClass(PolicyChangeAudit.class);
        verify(auditRepository).saveAndFlush(auditCaptor.capture());
        assertThat(saved).isSameAs(auditCaptor.getValue());
        assertThat(saved.getPolicyId()).isEqualTo(44L);
        assertThat(saved.getOperation()).isEqualTo(PolicyChangeOperation.UPDATE);
        assertThat(saved.getBeforeSnapshot()).isEqualTo(before);
        assertThat(saved.getAfterSnapshot()).isEqualTo(after);
        assertThat(saved.getChangedByUserId()).isEqualTo(7L);
        assertThat(saved.getChangedByUsername()).isEqualTo("admin");
        assertThat(saved.getChangedAt()).isNotNull();

        setEntityId(saved, 8L);
        Pageable pageable = PageRequest.of(0, 100, Sort.by(
                Sort.Order.desc("changedAt"), Sort.Order.desc("id")
        ));
        when(auditRepository.findAll(
                ArgumentMatchers.<Specification<PolicyChangeAudit>>any(),
                any(Pageable.class)
        ))
                .thenReturn(new PageImpl<>(List.of(saved), pageable, 1));

        AuditPageResponse<PolicyChangeAuditResponse> response = service.searchForPolicy(
                44L, 0, 100, null, null, null, null
        );

        assertThat(response.content()).containsExactly(new PolicyChangeAuditResponse(
                8L,
                44L,
                PolicyChangeOperation.UPDATE,
                new PolicyChangeSnapshotResponse(
                        "Sensor Rule", "SENSOR", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW, true, "old"
                ),
                new PolicyChangeSnapshotResponse(
                        "Camera Rule", "CAMERA", "video-stream", PolicyAction.WRITE, PolicyEffect.DENY, false, "new"
                ),
                7L,
                "admin",
                saved.getChangedAt()
        ));
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
    }

    @Test
    void createAndDeleteEventsKeepTheirOneSidedSnapshots() {
        PolicyChangeAudit create = new PolicyChangeAudit(
                44L,
                PolicyChangeOperation.CREATE,
                null,
                new PolicySnapshot("Rule", "SENSOR", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW,
                        true, null),
                7L,
                "admin"
        );
        PolicyChangeAudit delete = new PolicyChangeAudit(
                44L,
                PolicyChangeOperation.DELETE,
                new PolicySnapshot("Rule", "SENSOR", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW,
                        true, null),
                null,
                7L,
                "admin"
        );

        assertThat(create.getBeforeSnapshot()).isNull();
        assertThat(create.getAfterSnapshot().name()).isEqualTo("Rule");
        assertThat(delete.getBeforeSnapshot().name()).isEqualTo("Rule");
        assertThat(delete.getAfterSnapshot()).isNull();
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
