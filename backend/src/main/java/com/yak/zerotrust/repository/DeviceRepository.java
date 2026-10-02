package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    List<Device> findAllByOrderByDeviceCodeAsc();

    Optional<Device> findByDeviceCode(String deviceCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.deviceCode = :deviceCode")
    Optional<Device> findByDeviceCodeForUpdate(@Param("deviceCode") String deviceCode);

    boolean existsByDeviceCode(String deviceCode);

    boolean existsByMqttClientId(String mqttClientId);

    boolean existsByDeviceCodeAndIdNot(String deviceCode, Long id);

    boolean existsByMqttClientIdAndIdNot(String mqttClientId, Long id);
}
