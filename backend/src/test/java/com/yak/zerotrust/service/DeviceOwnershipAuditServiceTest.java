package com.yak.zerotrust.service;

import com.yak.zerotrust.dto.AuditPageResponse;
import com.yak.zerotrust.dto.DeviceOwnershipAuditResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceOwnershipAudit;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceOwnershipAuditRepository;
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
class DeviceOwnershipAuditServiceTest {

    @Mock
    private DeviceOwnershipAuditRepository ownershipAuditRepository;

    @Test
    void persistsAndReturnsTransferSnapshots() {
        UserAccount previousOwner = user("previous", "Previous Owner", 17L);
        UserAccount newOwner = user("new-owner", "New Owner", 18L);
        Device device = new Device(
                "SENSOR-003",
                "Temperature Sensor 3",
                DeviceType.SENSOR,
                "192.168.10.24",
                "SENSOR-003",
                previousOwner
        );
        setEntityId(device, 21L);
        when(ownershipAuditRepository.saveAndFlush(any(DeviceOwnershipAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        DeviceOwnershipAuditService service = new DeviceOwnershipAuditService(ownershipAuditRepository);

        DeviceOwnershipAudit saved = service.recordTransfer(device, previousOwner, newOwner, 7L, "admin");

        ArgumentCaptor<DeviceOwnershipAudit> auditCaptor = ArgumentCaptor.forClass(DeviceOwnershipAudit.class);
        verify(ownershipAuditRepository).saveAndFlush(auditCaptor.capture());
        assertThat(saved).isSameAs(auditCaptor.getValue());
        assertThat(saved.getDeviceId()).isEqualTo(21L);
        assertThat(saved.getDeviceCode()).isEqualTo("SENSOR-003");
        assertThat(saved.getPreviousOwnerId()).isEqualTo(17L);
        assertThat(saved.getPreviousOwnerUsername()).isEqualTo("previous");
        assertThat(saved.getNewOwnerId()).isEqualTo(18L);
        assertThat(saved.getNewOwnerUsername()).isEqualTo("new-owner");
        assertThat(saved.getChangedByUserId()).isEqualTo(7L);
        assertThat(saved.getChangedByUsername()).isEqualTo("admin");
        assertThat(saved.getChangedAt()).isNotNull();

        setEntityId(saved, 31L);
        Pageable pageable = PageRequest.of(0, 100, Sort.by(
                Sort.Order.desc("changedAt"), Sort.Order.desc("id")
        ));
        when(ownershipAuditRepository.findAll(
                ArgumentMatchers.<Specification<DeviceOwnershipAudit>>any(),
                any(Pageable.class)
        ))
                .thenReturn(new PageImpl<>(List.of(saved), pageable, 1));

        AuditPageResponse<DeviceOwnershipAuditResponse> response = service.searchForDevice(
                21L, 0, 100, null, null, null, null
        );

        assertThat(response.content()).containsExactly(new DeviceOwnershipAuditResponse(
                31L,
                21L,
                "SENSOR-003",
                17L,
                "previous",
                18L,
                "new-owner",
                7L,
                "admin",
                saved.getChangedAt()
        ));
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
    }

    private UserAccount user(String username, String fullName, Long id) {
        UserAccount user = new UserAccount(username, "hash", fullName, UserRole.USER, true);
        setEntityId(user, id);
        return user;
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
