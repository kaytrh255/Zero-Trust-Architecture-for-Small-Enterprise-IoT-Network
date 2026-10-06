package com.yak.zerotrust.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/** Issues high-entropy MQTT passwords; Mosquitto Dynamic Security owns authentication. */
@Service
public class DeviceCredentialService {

    private static final int PASSWORD_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    public String issueMqttPassword() {
        byte[] randomBytes = new byte[PASSWORD_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
