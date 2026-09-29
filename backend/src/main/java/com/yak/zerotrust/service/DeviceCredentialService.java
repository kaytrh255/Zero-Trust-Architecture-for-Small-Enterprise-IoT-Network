package com.yak.zerotrust.service;

import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.repository.DeviceRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

@Service
public class DeviceCredentialService {

    private static final int TOKEN_BYTES = 32;

    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceCredentialService(DeviceRepository deviceRepository, PasswordEncoder passwordEncoder) {
        this.deviceRepository = deviceRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public IssuedCredential issue() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return new IssuedCredential(token, passwordEncoder.encode(token));
    }

    @Transactional
    public Optional<Device> authenticate(String deviceCode, String token) {
        if (deviceCode == null || token == null || token.isBlank()) {
            return Optional.empty();
        }

        String normalizedCode = deviceCode.trim().toUpperCase(Locale.ROOT);
        return deviceRepository.findByDeviceCodeForUpdate(normalizedCode)
                .filter(device -> matches(token, device.getMqttCredentialHash()));
    }

    public boolean matches(String token, String credentialHash) {
        return token != null
                && !token.isBlank()
                && credentialHash != null
                && !credentialHash.isBlank()
                && passwordEncoder.matches(token, credentialHash);
    }

    public record IssuedCredential(String token, String passwordHash) {
    }
}
