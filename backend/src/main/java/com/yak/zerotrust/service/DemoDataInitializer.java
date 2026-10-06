package com.yak.zerotrust.service;

import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{3,50}$");

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminFullName;
    private final String adminPassword;

    public DemoDataInitializer(
            UserRepository userRepository,
            DeviceRepository deviceRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin.username}") String adminUsername,
            @Value("${app.bootstrap.admin.full-name}") String adminFullName,
            @Value("${app.bootstrap.admin.password}") String adminPassword
    ) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername.trim().toLowerCase(Locale.ROOT);
        this.adminFullName = adminFullName.trim();
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        validateBootstrapCredentials();
        UserAccount admin = getOrCreateAdmin();
        seedDevice("SENSOR-001", "Temperature Sensor 1", DeviceType.SENSOR,
                "192.168.10.21", "SENSOR-001", DeviceStatus.ACTIVE, admin);
        seedDevice("CAMERA-001", "Camera 1", DeviceType.CAMERA,
                "192.168.10.22", "CAMERA-001", DeviceStatus.ACTIVE, admin);
        seedDevice("SENSOR-002", "Temperature Sensor 2", DeviceType.SENSOR,
                "192.168.10.23", "SENSOR-002", DeviceStatus.BLOCKED, admin);
    }

    private UserAccount getOrCreateAdmin() {
        return userRepository.findByUsername(adminUsername)
                .map(existing -> {
                    if (existing.getRole() != UserRole.ADMIN) {
                        throw new IllegalStateException(
                                "Bootstrap username already belongs to a non-admin account; set a different ADMIN_USERNAME"
                        );
                    }
                    return existing;
                })
                .orElseGet(() -> userRepository.save(new UserAccount(
                        adminUsername,
                        passwordEncoder.encode(adminPassword),
                        adminFullName,
                        UserRole.ADMIN,
                        true
                )));
    }

    private void seedDevice(
            String deviceCode,
            String deviceName,
            DeviceType deviceType,
            String ipAddress,
            String mqttClientId,
            DeviceStatus status,
            UserAccount owner
    ) {
        if (deviceRepository.existsByDeviceCode(deviceCode)
                || deviceRepository.existsByMqttClientId(mqttClientId)) {
            return;
        }
        Device device = new Device(deviceCode, deviceName, deviceType, ipAddress, mqttClientId, owner);
        device.changeStatus(status);
        deviceRepository.save(device);
    }

    private void validateBootstrapCredentials() {
        int passwordLength = adminPassword.getBytes(StandardCharsets.UTF_8).length;
        if (!USERNAME_PATTERN.matcher(adminUsername).matches()) {
            throw new IllegalArgumentException("ADMIN_USERNAME must be 3-50 characters using letters, numbers, dot, underscore, or hyphen");
        }
        if (adminFullName.isBlank() || adminFullName.length() > 100) {
            throw new IllegalArgumentException("ADMIN_FULL_NAME must contain 1-100 characters");
        }
        if (passwordLength < 8 || passwordLength > 72) {
            throw new IllegalArgumentException("ADMIN_PASSWORD must be 8-72 UTF-8 bytes for BCrypt");
        }
    }
}
