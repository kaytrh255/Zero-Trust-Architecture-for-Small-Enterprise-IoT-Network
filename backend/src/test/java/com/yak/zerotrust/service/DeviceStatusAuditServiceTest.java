package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.DeviceStatusAuditResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceStatusAudit;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceStatusAuditRepository;
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
class DeviceStatusAuditServiceTest {

    @Mock
    private DeviceStatusAuditRepository auditRepository;

    @Test
    void persistsAndReturnsStatusTransitionSnapshots() {
        UserAccount owner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                owner
        );
        setEntityId(device, 21L);
        when(auditRepository.saveAndFlush(any(DeviceStatusAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DeviceStatusAuditService service = new DeviceStatusAuditService(auditRepository);

        DeviceStatusAudit saved = service.recordChange(
                device,
                DeviceStatus.ACTIVE,
                DeviceStatus.BLOCKED,
                7L,
                "admin"
        );

        ArgumentCaptor<DeviceStatusAudit> auditCaptor = ArgumentCaptor.forClass(DeviceStatusAudit.class);
        verify(auditRepository).saveAndFlush(auditCaptor.capture());
        assertThat(saved).isSameAs(auditCaptor.getValue());
        assertThat(saved.getDeviceId()).isEqualTo(21L);
        assertThat(saved.getDeviceCode()).isEqualTo("SENSOR-003");
        assertThat(saved.getPreviousStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(saved.getNewStatus()).isEqualTo(DeviceStatus.BLOCKED);
        assertThat(saved.getChangedByUserId()).isEqualTo(7L);
        assertThat(saved.getChangedByUsername()).isEqualTo("admin");
        assertThat(saved.getChangedAt()).isNotNull();

        setEntityId(saved, 31L);
        Pageable pageable = PageRequest.of(0, 100, Sort.by(
                Sort.Order.desc("changedAt"), Sort.Order.desc("id")
        ));
        when(auditRepository.findAll(
                ArgumentMatchers.<Specification<DeviceStatusAudit>>any(),
                any(Pageable.class)
        ))
                .thenReturn(new PageImpl<>(List.of(saved), pageable, 1));

        AuditPageResponse<DeviceStatusAuditResponse> response = service.searchForDevice(
                21L, 0, 100, null, null, null, null
        );

        assertThat(response.content()).containsExactly(new DeviceStatusAuditResponse(
                31L,
                21L,
                "SENSOR-003",
                DeviceStatus.ACTIVE,
                DeviceStatus.BLOCKED,
                7L,
                "admin",
                saved.getChangedAt()
        ));
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
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
