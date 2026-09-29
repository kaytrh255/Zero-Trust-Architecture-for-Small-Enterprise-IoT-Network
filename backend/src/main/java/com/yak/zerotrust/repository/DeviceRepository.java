package com.yak.zerotrust.repository;

import com.yak.zerotrust.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    List<Device> findAllByOrderByDeviceCodeAsc();

    Optional<Device> findByDeviceCode(String deviceCode);

    boolean existsByDeviceCode(String deviceCode);

    boolean existsByMqttClientId(String mqttClientId);

    boolean existsByDeviceCodeAndIdNot(String deviceCode, Long id);

    boolean existsByMqttClientIdAndIdNot(String mqttClientId, Long id);
}
